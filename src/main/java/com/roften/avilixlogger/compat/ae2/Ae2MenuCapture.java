package com.roften.avilixlogger.compat.ae2;

/** Implemented by the optional AEBaseMenu mixin for nesting packet/menu actions. */
public interface Ae2MenuCapture {
    void avilixlogger$beforeAction();
    void avilixlogger$afterAction();
}
