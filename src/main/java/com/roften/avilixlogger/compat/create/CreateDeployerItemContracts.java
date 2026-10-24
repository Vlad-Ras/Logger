package com.roften.avilixlogger.compat.create;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.util.Map;

/** Audited item implementations whose effects are covered by the joint endpoints.
 * IDs alone are insufficient: a replacement item must not inherit another implementation's contract.
 * No Create classes are loaded here, so detached validation also works without Create installed.
 */
public final class CreateDeployerItemContracts {
    private record Contract(String implementation,String effects) {}
    private static final Map<String,Contract> CONTRACTS=Map.of(
            "create:sand_paper",new Contract("com.simibubi.create.content.equipment.sandPaper.SandPaperItem","sandpaper"),
            "create:red_sand_paper",new Contract("com.simibubi.create.content.equipment.sandPaper.SandPaperItem","sandpaper"),
            "create:bar_of_chocolate",new Contract("net.minecraft.world.item.Item","food"),
            "create:sweet_roll",new Contract("net.minecraft.world.item.Item","food"),
            "create:chocolate_glazed_berries",new Contract("net.minecraft.world.item.Item","food"),
            "create:honeyed_apple",new Contract("net.minecraft.world.item.Item","food"),
            "create:builders_tea",new Contract("com.simibubi.create.content.equipment.BuildersTeaItem","food_and_bottle"),
            "create:cardboard_sword",new Contract("com.simibubi.create.content.equipment.tool.CardboardSwordItem","knockback_and_slowness"));
    private static final Map<String,String> FARMERS_DELIGHT=Map.of(
            "ConsumableItem","meal_and_container", "DrinkableItem","drink_and_container",
            "HotCocoaItem","cure_harmful_and_bottle", "MilkBottleItem","cure_and_bottle",
            "MelonJuiceItem","heal_and_bottle", "PopsicleItem","extinguish");
    private CreateDeployerItemContracts() {}

    private static Contract contract(String id,String implementation,boolean food,boolean nourishment) {
        var known=CONTRACTS.get(id);if(known!=null)return known;
        if(food&&implementation.equals("net.minecraft.world.item.Item"))return new Contract(implementation,nourishment?"food_with_nourishment":"food");
        String prefix="vectorwing.farmersdelight.common.item.";
        if(implementation.startsWith(prefix)){
            var effects=FARMERS_DELIGHT.get(implementation.substring(prefix.length()));
            if(effects!=null)return new Contract(implementation,effects);
        }
        return null;
    }

    private static String provider(Contract contract,String id){return CONTRACTS.containsKey(id)?"create":contract.implementation().startsWith("vectorwing.")||contract.effects().equals("food_with_nourishment")?"farmersdelight":"minecraft";}
    private static boolean supportedVersion(String provider,String version){return switch(provider){case "create"->java.util.Set.of("6.0.10","6.0.11").contains(version);case "farmersdelight"->java.util.Set.of("1.3.2","1.3.4").contains(version);case "minecraft"->version.equals("1.21.1");default->false;};}
    private static String verifyProvider(String provider,String expected){
        String actual=net.neoforged.fml.ModList.get().getModContainerById(provider).orElseThrow(()->new IllegalStateException("Нет мода контракта: "+provider)).getModInfo().getVersion().toString();
        if(!supportedVersion(provider,actual)||expected!=null&&!actual.equals(expected))throw new IllegalStateException("Версия мода не проверена контрактом активатора: "+provider+" "+actual);
        return actual;
    }

    public static CompoundTag describe(ItemStack stack) {
        var tag=new CompoundTag();
        if(stack.isEmpty())return tag;
        String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if(id.startsWith("minecraft:"))return tag;
        var food=stack.get(net.minecraft.core.component.DataComponents.FOOD);
        boolean nourishment=food!=null&&food.effects().stream().anyMatch(e->e.effect().getEffect().unwrapKey().orElseThrow().location().toString().equals("farmersdelight:nourishment"));
        var contract=contract(id,stack.getItem().getClass().getName(),food!=null,nourishment);
        if(contract==null||!stack.getItem().getClass().getName().equals(contract.implementation()))
            throw new IllegalStateException("Нет контракта предмета активатора: "+id);
        if(food!=null)for(var effect:food.effects()){
            var holder=effect.effect().getEffect();String key=holder.unwrapKey().orElseThrow().location().toString();
            if(!key.startsWith("minecraft:")){
                if(CONTRACTS.containsKey(id)||!key.equals("farmersdelight:nourishment")||!holder.value().getClass().getName().equals("vectorwing.farmersdelight.common.effect.NourishmentEffect"))
                    throw new IllegalStateException("Модовый эффект еды требует отдельного контракта: "+key);
                verifyProvider("farmersdelight",null);
            }
        }
        if(contract.effects().equals("knockback_and_slowness")){
            var enchantments=stack.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
            if(enchantments!=null)for(var enchantment:enchantments.keySet())if(!enchantment.unwrapKey().orElseThrow().location().toString().equals("minecraft:knockback"))
                throw new IllegalStateException("Неподдержанные чары картонного меча");
        }
        tag.putString("Item",id);tag.putInt("Version",1);
        tag.putString("Implementation",contract.implementation());tag.putString("Effects",contract.effects());
        String provider=provider(contract,id);tag.putString("Provider",provider);tag.putString("ProviderVersion",verifyProvider(provider,null));
        return tag;
    }

    public static void validate(CompoundTag tag) {
        String id=tag.getString("Item");var key=net.minecraft.resources.ResourceLocation.tryParse(id);
        var contract=contract(id,tag.getString("Implementation"),tag.getString("Effects").equals("food")||tag.getString("Effects").equals("food_with_nourishment"),tag.getString("Effects").equals("food_with_nourishment"));
        if(key==null||key.getNamespace().equals("minecraft")||contract==null||!tag.contains("Version",3)||tag.getInt("Version")!=1||tag.getAllKeys().size()!=6
                ||!tag.getString("Implementation").equals(contract.implementation())||!tag.getString("Effects").equals(contract.effects()))
            throw new IllegalStateException("Контракт модового предмета отсутствует или изменён");
        String provider=provider(contract,id);if(!tag.getString("Provider").equals(provider)||!supportedVersion(provider,tag.getString("ProviderVersion")))throw new IllegalStateException("Изменена версия поставщика контракта");
    }

    public static void verifyImplementation(CompoundTag tag) {
        validate(tag);
        verifyProvider(tag.getString("Provider"),tag.getString("ProviderVersion"));
        var id=net.minecraft.resources.ResourceLocation.parse(tag.getString("Item"));
        if(!BuiltInRegistries.ITEM.containsKey(id)||!BuiltInRegistries.ITEM.get(id).getClass().getName().equals(tag.getString("Implementation")))
            throw new IllegalStateException("Реализация модового предмета заменена: "+id);
    }
}
