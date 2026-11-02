package com.roften.avilixlogger.compat.opac;
import com.roften.avilixlogger.core.CauseContext;
public interface OpenPacTaskCause {
    void avilixlogger$bindCause(CauseContext.Cause cause);
    CauseContext.Cause avilixlogger$cause();
}
