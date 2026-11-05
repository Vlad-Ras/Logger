package com.roften.avilixlogger.compat.opac.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.opac.OpenPacStateAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import com.roften.avilixlogger.compat.opac.OpenPacGroupOwner;
@Pseudo
@Mixin(targets = "xaero.pac.common.player.config.group.custom.CustomPlayerConfigGroupData", remap = false)
public abstract class GroupDataMixin implements OpenPacGroupOwner {
    @Unique private Object avilixlogger$owner;
    public Object avilixlogger$group() { return avilixlogger$owner; }
    public void avilixlogger$group(Object group) { avilixlogger$owner = group; }
    @WrapMethod(method = "includeMember(Lxaero/pac/common/player/config/group/custom/CustomPlayerGroupMember;)V", remap = false)
    private void avilixlogger$include(@Coerce Object member, Operation<Void> original) {
        if (!CaptureHandlers.OPAC_GROUPDATAMIXIN_INCLUDE.enabled()) { original.call(member); return; }
        var action = OpenPacStateAudit.groupData(this, "group-data-include"); boolean success = false;
        try { original.call(member); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "excludeMember(Lxaero/pac/common/player/config/group/custom/CustomPlayerGroupMember;)Z", remap = false)
    private boolean avilixlogger$exclude(@Coerce Object member, Operation<Boolean> original) {
        if (!CaptureHandlers.OPAC_GROUPDATAMIXIN_EXCLUDE.enabled()) { return original.call(member); }
        var action = OpenPacStateAudit.groupData(this, "group-data-exclude"); boolean success = false;
        try { var result = original.call(member); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "includeGroup(Ljava/lang/String;)V", remap = false)
    private void avilixlogger$includeGroup(String id, Operation<Void> original) {
        if (!CaptureHandlers.OPAC_GROUPDATAMIXIN_INCLUDEGROUP.enabled()) { original.call(id); return; }
        var action = OpenPacStateAudit.groupData(this, "group-data-includeGroup"); boolean success = false;
        try { original.call(id); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "excludeGroup(Ljava/lang/String;)Z", remap = false)
    private boolean avilixlogger$excludeGroup(String id, Operation<Boolean> original) {
        if (!CaptureHandlers.OPAC_GROUPDATAMIXIN_EXCLUDEGROUP.enabled()) { return original.call(id); }
        var action = OpenPacStateAudit.groupData(this, "group-data-excludeGroup"); boolean success = false;
        try { var result = original.call(id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "confirmMemberId(Lxaero/pac/common/player/config/group/custom/CustomPlayerGroupMember;Ljava/util/UUID;)Lxaero/pac/common/player/config/group/custom/CustomPlayerGroupMember;", remap = false)
    private Object avilixlogger$confirmId(@Coerce Object member, UUID id, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_GROUPDATAMIXIN_CONFIRMID.enabled()) { return original.call(member, id); }
        var action = OpenPacStateAudit.groupData(this, "group-data-confirmId"); boolean success = false;
        try { var result = original.call(member, id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "updateMemberName(Ljava/util/UUID;Ljava/lang/String;)Z", remap = false)
    private boolean avilixlogger$updateName(UUID id, String name, Operation<Boolean> original) {
        if (!CaptureHandlers.OPAC_GROUPDATAMIXIN_UPDATENAME.enabled()) { return original.call(id, name); }
        var action = OpenPacStateAudit.groupData(this, "group-data-updateName"); boolean success = false;
        try { var result = original.call(id, name); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
}
