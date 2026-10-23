package com.roften.avilixlogger.core;

/** Regression: LuckPerms permission grants cannot override missing server OP status. */
public final class OperatorAccessTest {
    public static void run() {
        check(!PermissionUtil.operatorGate(false, false), "unauthorized non-op allowed");
        check(!PermissionUtil.operatorGate(false, true), "authorized server promoted non-op");
        check(!PermissionUtil.operatorGate(true, false), "OP bypassed AvilixAuthCore");
        check(PermissionUtil.operatorGate(true, true), "authorized OP denied");
        System.out.println("OPERATOR_ACCESS_OK: admin-only, no permission-only promotion");
    }
    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
    }
}
