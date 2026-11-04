package com.roften.avilixlogger.compat.create;

/** Native loading replaces a zero enchantment seed; rollback must preserve it exactly. */
public interface CreatePlayerRuntimeAccess {
    void avilixlogger$enchantmentSeed(int seed);
}
