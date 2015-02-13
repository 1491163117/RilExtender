/**
 * Copyright (c) 2014 Joey Hewitt
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.scintill.rilextender;

import android.content.BroadcastReceiver;
import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger; // XXX should get rid of this eventually, lazy hack

/**
 * RilExtender - access to Android telephony internals
 *
 * This class is loaded in the running com.android.phone process on the main thread.
 * Its static{} block sets everything up.
 *
 * Since broadcasts were the only type of high-level IPC I could find, that didn't need to be
 * defined in the manifest, that's what we use to receive requests and return answers.  It's a bit
 * of a mess, so I may want to find something else.
 *
 * Example command line invocation:
 * am broadcast -a net.scintill.rilextender.iccio --ei command 192 --ei fileID 28542 --es path 3F007F20 --ei p3 15 com.android.phone
 */
public class RilExtender implements Handler.Callback {
    private static final String TAG = "RilExtender";
    public static final int VERSION = 11; // this isn't critical, just for keeping track of what's "installed"

    private void iccIOForApp(PendingResult result, int command, int fileId, String path, int p1, int p2, int p3, String data, String pin2, String aid) {
        Log.d(TAG, "simIo " + fileId + " " + command + " " + p1 + " " + p2 + " " + p3 + " " + path);

        try {
            Object RIL = getCommandsInterface(getGsmPhone());

            RIL.getClass().getMethod("iccIOForApp",
                    int.class, int.class, String.class, int.class, int.class,
                    int.class, String.class, String.class, String.class,
                    Message.class)

                    .invoke(RIL, command, fileId, !mIsMTK ? path : null, p1, p2, p3, data, pin2, aid,
                        mRILResponseThreadHandler.obtainMessage(MSG_ICCIOFORAPP, result));
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private void oemRilRequestStrings(PendingResult result, String[] requestArgs) {
        Log.d(TAG, "oemRilRequestStrings "+requestArgs);

        Object RIL = getCommandsInterface(getGsmPhone());

        try {
            RIL.getClass().getMethod("invokeOemRilRequestStrings",
                    String[].class, Message.class)
                    .invoke(RIL, requestArgs,
                        mRILResponseThreadHandler.obtainMessage(MSG_RILREQUESTSTRINGS, result));
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private void oemRilRequestRaw(PendingResult result, String requestArgHex) {
        Log.d(TAG, "oemRilRequestRaw "+requestArgHex);

        try {
            Object RIL = getCommandsInterface(getGsmPhone());

            RIL.getClass().getMethod("invokeOemRilRequestRaw",
                    byte[].class, Message.class)
                    .invoke(RIL, new BigInteger(requestArgHex, 16).toByteArray(),
                            mRILResponseThreadHandler.obtainMessage(MSG_RILREQUESTRAW, result));
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private void ping(PendingResult result) {
        Bundle b = result.getResultExtras(false);
        b.putLong("birthdate", mBirthDate);
        b.putInt("version", VERSION);
        returnResult(result);
    }

    private static final int MSG_ICCIOFORAPP = 1;
    private static final int MSG_RILREQUESTSTRINGS = 2;
    private static final int MSG_RILREQUESTRAW = 3;

    @Override
    public boolean handleMessage(Message msg) {
        Object asyncResult = msg.obj;

        try {
            PendingResult result = (PendingResult) asyncResult.getClass().getField("userObj").get(asyncResult);

            Throwable asyncResultException = (Throwable) asyncResult.getClass().getField("exception").get(asyncResult);
            if (asyncResultException != null) {
                return returnThrowable(result, asyncResultException);
            }

            if (msg.what == MSG_ICCIOFORAPP) {
                // exception handling based on IccFileHandler#processException()

                Object iccIoResult = asyncResult.getClass().getField("result").get(asyncResult);
                Class iccIoResultClass = iccIoResult.getClass();
                Throwable iccIoException = (Throwable) iccIoResultClass.getMethod("getException").invoke(iccIoResult);
                if (iccIoException != null) {
                    return returnThrowable(result, iccIoException);
                }

                int sw1 = iccIoResultClass.getField("sw1").getInt(iccIoResult);
                int sw2 = iccIoResultClass.getField("sw2").getInt(iccIoResult);
                byte[] payload = (byte[]) iccIoResultClass.getField("payload").get(iccIoResult);

                byte[] fullResponse = new byte[payload.length + 2];
                fullResponse[0] = (byte) (sw1 & 0xff);
                fullResponse[1] = (byte) (sw2 & 0xff);
                System.arraycopy(payload, 0, fullResponse, 2, payload.length);

                result.getResultExtras(false).putByteArray("return", fullResponse);
            } else if (msg.what == MSG_RILREQUESTSTRINGS) {
                result.getResultExtras(false).putStringArray("return",
                        (String[]) asyncResult.getClass().getField("result").get(asyncResult));
            } else if (msg.what == MSG_RILREQUESTRAW) {
                result.getResultExtras(false).putByteArray("return",
                        (byte[]) asyncResult.getClass().getField("result").get(asyncResult));
            } else {
                throw new RuntimeException("unknown msg "+msg);
            }
            return returnResult(result);
        } catch (InvocationTargetException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean returnResult(PendingResult result) {
        result.setResultCode(1);
        result.setResultData(toString(result.getResultExtras(false))); // XXX for convenience at shell
        result.finish();
        return true;
    }

    private boolean returnThrowable(PendingResult result, Throwable t) {
        result.setResultCode(-1);
        result.setResultData(t.getMessage()); // XXX for convenience at shell
        result.getResultExtras(false).putString("exception", t.getMessage());
        result.finish();
        return true;
    }

    /**
     * Must be done on main thread
     * @return The GSMPhone instance.
     */
    private static Object getGsmPhone() {
        // Adapted from frameworks/opt/telephony/src/java/com/android/internal/telephony/DebugService.java
        try {
            Object phoneProxy = Class.forName("com.android.internal.telephony.PhoneFactory").getMethod("getDefaultPhone").invoke(null);
            // Mediatek is special
            if (phoneProxy.getClass().getName().equals("com.android.internal.telephony.gemini.GeminiPhone")) {
                phoneProxy = phoneProxy.getClass().getMethod("getDefaultPhone").invoke(phoneProxy);
                mIsMTK = true;
            }

            Object gsmPhone = phoneProxy.getClass().getMethod("getActivePhone").invoke(phoneProxy);
            return gsmPhone;
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return The CommandsInterface instance (RIL object)
     */
    private static Object getCommandsInterface(Object gsmPhone) {
        Field f = null;
        try {
            f = gsmPhone.getClass().getField("mCi");
        } catch (NoSuchFieldException e) {
            try {
                f = gsmPhone.getClass().getField("mCM");
            } catch (NoSuchFieldException e2) { /* fall through */ }
        }
        if (f == null) throw new RuntimeException("can't find CommandsInterface");

        try {
            return f.get(gsmPhone);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private Context mContext;

    private long mBirthDate;
    private static boolean mIsMTK = false;

    private HandlerThread mRILResponseThread;
    private Handler mRILResponseThreadHandler;

    private RilExtender() {
        mBirthDate = System.currentTimeMillis();

        try {
            Object gsmPhone = getGsmPhone();
            Context phoneContext = (Context) gsmPhone.getClass().getMethod("getContext").invoke(gsmPhone);
            mContext = (Context) phoneContext.getClass().getMethod("getApplicationContext").invoke(phoneContext);
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            Log.e(TAG, "Error finding context", e);
        }

        mRILResponseThread = new HandlerThread("RILResponseHandlerThread");
        mRILResponseThread.start();
        mRILResponseThreadHandler = new Handler(mRILResponseThread.getLooper(), this);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, final Intent intent) {
                // this enters on the main thread

                PendingResult result = goAsync();

                result.setResultExtras(new Bundle());
                try {
                    String action = intent.getAction();
                    Log.d(TAG, "onReceive "+action);
                    if (action.equals("net.scintill.rilextender.iccio")) {
                        iccIOForApp(result, getIntRequired(intent, "command"), getIntRequired(intent, "fileID"),
                                getStringRequired(intent, "path"), getIntRequired(intent, "p1"),
                                getIntRequired(intent, "p2"), getIntRequired(intent, "p3"),
                                getStringRequired(intent, "data"), getStringRequired(intent, "pin2"),
                                getStringRequired(intent, "aid"));
                    } else if (action.equals("net.scintill.rilextender.ping")) {
                        ping(result);
                    } else if (action.equals("net.scintill.rilextender.oemrilrequeststrings")) {
                        if (intent.getStringExtra("arg") != null) {
                            oemRilRequestStrings(result, new String[]{intent.getStringExtra("arg")});
                        } else {
                            oemRilRequestStrings(result, getStringArrayRequired(intent, "args"));
                        }
                    } else if (action.equals("net.scintill.rilextender.oemrilrequestraw")) {
                        oemRilRequestRaw(result, getStringRequired(intent, "argHex"));
                    } else {
                        Log.d(TAG, "Unknown action "+action);
                        result.finish();
                        return;
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "Uncaught exception", e);
                    returnThrowable(result, e);
                    return;
                }
            }
        };

        // These should be last, as we don't want to receive messages until everything's set up
        // XXX needs some security testing, to make sure other apps can't call this, or snoop on data
        // https://dev.guardianproject.info/projects/trustedintents/wiki
        // https://stackoverflow.com/questions/3304304/how-to-get-the-sender-of-an-intent
        // W/BroadcastQueue( 1896): Permission Denial: broadcasting Intent { act=net.scintill.rilextender.ping flg=0x10 pkg=com.android.phone } from null (pid=6930, uid=2000) requires permISSion due to registered receiver BroadcastFilter{4250bb68 u0 ReceiverList{41aadcf0 6560 com.android.phone/1001/u0 remote:41e74ca8}}
        mContext.registerReceiver(receiver, new IntentFilter("net.scintill.rilextender.iccio"), PERMISSION_ID, null);
        mContext.registerReceiver(receiver, new IntentFilter("net.scintill.rilextender.ping"), PERMISSION_ID, null);
        mContext.registerReceiver(receiver, new IntentFilter("net.scintill.rilextender.oemrilrequeststrings"), PERMISSION_ID, null);
        mContext.registerReceiver(receiver, new IntentFilter("net.scintill.rilextender.oemrilrequestraw"), PERMISSION_ID, null);

        // Raise a toast
        Toast toast = Toast.makeText(mContext, "RilExtender active", Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP | Gravity.RIGHT, 0, 0);
        toast.show();

        mContext.sendBroadcast(new Intent("net.scintill.rilextender.I_AM_RILEXTENDER"));
    }

    private static final String PERMISSION_ID = "net.scintill.rilextender.RILEXTENDER_CLIENT";

    private static String getStringRequired(Intent i, String k) {
        String s = null;
        try {
            s = (String)i.getExtras().get(k);
        } catch (ClassCastException | NullPointerException e) {} // fall through
        if (s == null) {
            throw new IllegalArgumentException("expected String extra: "+k);
        }
        return s;
    }

    private static int getIntRequired(Intent i, String k) {
        Integer integer = null;
        try {
            integer = (Integer)i.getExtras().get(k);
        } catch (ClassCastException | NullPointerException e) {} // fall through
        if (integer == null) {
            throw new IllegalArgumentException("expected int extra: "+k);
        }
        return integer;
    }

    private static String[] getStringArrayRequired(Intent i, String k) {
        String[] arr = null;
        try {
            arr = (String[])i.getExtras().get(k);
        } catch (ClassCastException | NullPointerException e) {} // fall through
        if (arr == null) {
            throw new IllegalArgumentException("expected String[] extra: "+k);
        }
        return arr;
    }

    private static String toString(Bundle bundle) {
        StringBuffer buff = new StringBuffer("Bundle[");
        boolean commaFlag = false;
        for (String k : bundle.keySet()) {
            if (commaFlag) buff.append(", ");
            commaFlag = true;

            buff.append(k).append('=');
            Object o = bundle.get(k);
            if (o instanceof byte[]) {
                buff.append(bytesToHexString((byte[])o));
            } else {
                buff.append(o);
            }
        }
        buff.append("]");
        return buff.toString();
    }

    /*
     * The following method is Copyright (C) 2006 The Android Open Source Project
     *
     * Licensed under the Apache License, Version 2.0 (the "License");
     * you may not use this file except in compliance with the License.
     * You may obtain a copy of the License at
     *
     *      http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     *
     *
     * Converts a byte array into a String of hexadecimal characters.
     *
     * @param bytes an array of bytes
     *
     * @return hex string representation of bytes array
     */
    public static String
    bytesToHexString(byte[] bytes) {
        if (bytes == null) return null;

        StringBuilder ret = new StringBuilder(2*bytes.length);

        for (int i = 0 ; i < bytes.length ; i++) {
            int b;

            b = 0x0f & (bytes[i] >> 4);

            ret.append("0123456789abcdef".charAt(b));

            b = 0x0f & bytes[i];

            ret.append("0123456789abcdef".charAt(b));
        }

        return ret.toString();
    }

    // this gets invoked by native injection code
    static {
        new RilExtender();
    }

}
