package com.roften.avilixlogger.compat.supplementaries;

import net.minecraft.network.chat.Component;
import java.util.List;

/** Read-only comparison of the already selected native holder; no owner lookup. */
public interface TextHolderAuditAccess {
    boolean avilixlogger$matches(int firstLine, List<? extends Component> messages);
}
