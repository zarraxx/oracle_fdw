# JNI backend Java bridge

This directory is reserved for the Java side of the experimental JDBC backend.

The intended shape is a small bridge class that wraps Oracle JDBC Thin and is
called from `jni_backend/oracle_utils_jni.c` through JNI.
