package com.arbelonson.ozen

fun javaHeapInUse(): Long {
    val runtime = Runtime.getRuntime()
    repeat(3) {
        runtime.gc()
        System.runFinalization()
    }
    return runtime.totalMemory() - runtime.freeMemory()
}
