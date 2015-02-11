/*
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
 *
 *
 *
 *  Based on smsdispatch.c from:
 *  Collin's Dynamic Dalvik Instrumentation Toolkit for Android
 *  Collin Mulliner <collin[at]mulliner.org>
 *
 *  (c) 2012,2013
 *
 *  License: LGPL v2.1
 *
 */

// TODO review https://developer.android.com/training/articles/perf-jni.html , if this is going to be "production quality"
// TODO look into this logcat message: "W/linker  (27134): librilinject.so has text relocations. This is wasting memory and is a security risk. Please fix."
// TODO gracefully handle failures like permissions errors, instead of crashing the phone process? or maybe crashing is the easy way to clean up?

#define _GNU_SOURCE
#include <stdio.h>
#include <dlfcn.h>
#include <sys/epoll.h>
#include <sys/types.h>
#include <unistd.h>

#include "hook.h"
#include "base.h"

#include <jni.h>

#include <android/log.h>

static struct hook_t eph;

#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "librilinject", __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, "librilinject", __VA_ARGS__)
#if 0
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "librilinject", __VA_ARGS__)
#else
#define ALOGD(...)
#endif

static jclass loadClassFromDex(JNIEnv *env, const char *classNameSlash, const char *classNameDot, const char *dexPath, const char *cachePath) {
	jclass clTargetClass = (*env)->FindClass(env, classNameSlash);

	if (!clTargetClass) {
		(*env)->ExceptionClear(env); // FindClass() complains if there's an exception already

		// Load my class with BaseDexClassLoader
		// See RilExtenderCommandsInterface.java:
		// new BaseDexClassLoader(rilExtenderDex.getAbsolutePath(), rilExtenderDexCacheDir, null, ClassLoader.getSystemClassLoader())

		jclass clFile = (*env)->FindClass(env, "java/io/File");
		jmethodID mFileConstructor = (*env)->GetMethodID(env, clFile, "<init>", "(Ljava/lang/String;)V");
		jobject obCacheDirFile = NULL;
		if (clFile && mFileConstructor) {
			obCacheDirFile = (*env)->NewObject(env, clFile, mFileConstructor, (*env)->NewStringUTF(env, cachePath));
			if ((*env)->ExceptionOccurred(env)) {
				ALOGE("new File() threw an exception");
				(*env)->ExceptionDescribe(env);
			}
		} else {
			ALOGE("Couldn't open cache File!");
		}

		jclass clDexClassLoader = (*env)->FindClass(env, "dalvik/system/BaseDexClassLoader");
		jmethodID mClassLoaderConstructor = (*env)->GetMethodID(env, clDexClassLoader, "<init>", "(Ljava/lang/String;Ljava/io/File;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
		jmethodID mGetSystemClassLoader = (*env)->GetStaticMethodID(env, clDexClassLoader, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
		jmethodID mLoadClass = (*env)->GetMethodID(env, clDexClassLoader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
		ALOGD("clDexClassLoader = %p, obCacheDirFile = %p", clDexClassLoader, obCacheDirFile);

		if (clDexClassLoader && mClassLoaderConstructor && mLoadClass && obCacheDirFile) {
			jobject classloaderobj = (*env)->NewObject(env, clDexClassLoader, mClassLoaderConstructor,
				(*env)->NewStringUTF(env, dexPath), obCacheDirFile, NULL,
				(*env)->CallStaticObjectMethod(env, clDexClassLoader, mGetSystemClassLoader));

			// XXX stingutf necesary?
			if (classloaderobj) {
				clTargetClass = (*env)->CallObjectMethod(env, classloaderobj, mLoadClass, (*env)->NewStringUTF(env, classNameDot));
				if ((*env)->ExceptionOccurred(env)) {
					ALOGE("loadClass() threw an exception");
					(*env)->ExceptionDescribe(env);
				} else {
					// this is enough to get Dalvik to execute <clinit> (class static initialization block) for us
					(*env)->GetStaticMethodID(env, clTargetClass, "<clinit>", "()V");
			    }
			} else {
				ALOGE("classloader object not found!");
			}
		} else {
			ALOGE("classloader/constructor not found!");
		}

		ALOGD("clTargetClass = %p", clTargetClass);
	}

	return clTargetClass;
}

static int my_epoll_wait(int epfd, struct epoll_event *events, int maxevents, int timeout) {
#define RETURN() return orig_epoll_wait(epfd, events, maxevents, timeout);

	ALOGI("my_epoll_wait()");

	int (*orig_epoll_wait)(int epfd, struct epoll_event *events, int maxevents, int timeout);
	orig_epoll_wait = (void*)eph.orig;
	// remove hook for epoll_wait
	hook_precall(&eph);

	void *pLibdvm = dlopen("libdvm.so", RTLD_LAZY);
	/*Thread*/void* (*dvmThreadSelf)() = 0;
	JNIEnv* (*dvmCreateJNIEnv)(/*Thread*/void*) = 0;
	void (*dvmDestroyJNIEnv)(JNIEnv *) = 0;

	if (pLibdvm) {
		dvmThreadSelf = dlsym(pLibdvm, "_Z13dvmThreadSelfv");
		dvmCreateJNIEnv = dlsym(pLibdvm, "_Z15dvmCreateJNIEnvP6Thread");
		dvmDestroyJNIEnv = dlsym(pLibdvm, "_Z16dvmDestroyJNIEnvP7_JNIEnv");
	}

	ALOGD("dvmThreadSelf = %p", dvmThreadSelf);
	ALOGD("dvmCreateJNIEnv = %p", dvmCreateJNIEnv);
	ALOGD("dvmDestroyJNIEnv = %p", dvmDestroyJNIEnv);

	if (!(dvmThreadSelf && dvmCreateJNIEnv && dvmDestroyJNIEnv)) {
		ALOGE("error finding libdvm funcs: %p %p %p %p", pLibdvm, dvmThreadSelf, dvmCreateJNIEnv, dvmDestroyJNIEnv);
		RETURN();
	}

	void *pThread = dvmThreadSelf();
	ALOGD("pThread = %p", pThread);
	if (!pThread) {
		ALOGE("error getting dvmThreadSelf()");
		RETURN();
	}

	JNIEnv* env = dvmCreateJNIEnv(pThread);
	ALOGD("JNIEnv* = %p", env);

	if (!env) {
		ALOGE("error getting JNIEnv");
		RETURN();
	}

	jclass c = loadClassFromDex(env, "net/scintill/rilextender/RilExtender", "net.scintill.rilextender.RilExtender",
		"/data/data/net.scintill.rilextender/app_rilextender/rilextender.dex", "/data/data/net.scintill.rilextender/app_rilextender-cache");

	if (!c) {
		ALOGE("error in loadClassFromDex");
		// fall through
	}

	dvmDestroyJNIEnv(env);

	RETURN();

#undef RETURN()
}


static void my_log(char *msg) {
	ALOGD("%s", msg);
}

// entry point when this library is loaded
void __attribute__ ((constructor)) my_init() {
	ALOGI("my_init()");

	// set log function for libbase
	set_logfunction(my_log);

	// If I try to load the class directly here, I get
	// "Optimized data directory /data/data/net.scintill.rilextender/app_rilextender-cache is not owned by the current user.
	// Shared storage cannot protect your application from code injection attacks.", despite having taken care to set that ownership
	// correctly.  The backtrace starts at android.os.MessageQueue.nativePollOnce. I guess epoll_wait was chosen by
	// Collin Mulliner as a sane-ish place in the call stack to do crazy stuff like this.
	hook(&eph, getpid(), "libc.", "epoll_wait", my_epoll_wait, 0);
}
