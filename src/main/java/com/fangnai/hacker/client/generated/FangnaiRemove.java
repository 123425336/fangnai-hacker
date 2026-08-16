package com.fangnai.hacker.client.generated;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fangnai.hacker.client.target.EntityBoxPicker;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.entity.PartEntity;

public class FangnaiRemove {
    private static final double CROSSHAIR_PICK_DISTANCE = 128.0D;
    private static volatile boolean loopEnabled;
    private static volatile String loopTarget = "all";
    private static volatile boolean loopIncludePlayers;

    public static String clearOnce(String target, boolean includePlayers) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return "FangnaiRemove 清除失败：客户端世界或玩家不存在。";
        }
        ClearStats stats = clearMatching(minecraft, normalizeTarget(target), includePlayers);
        return "FangnaiRemove 清除完成：" + stats.summary();
    }

    public static String startLoopClear(String target, boolean includePlayers) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return "FangnaiRemove 循环清除启动失败：客户端世界或玩家不存在。";
        }
        loopTarget = normalizeTarget(target);
        loopIncludePlayers = includePlayers;
        loopEnabled = true;
        return "FangnaiRemove 循环清除已启动：target=" + loopTarget + "；"
                + (includePlayers ? "包含其他玩家，永远跳过自己" : "跳过所有玩家") + "。";
    }

    public static String stopLoopClear() {
        boolean wasEnabled = loopEnabled;
        loopEnabled = false;
        return wasEnabled ? "FangnaiRemove 循环清除已停止。" : "FangnaiRemove 循环清除本来就未启动。";
    }

    public static void clientTick(Minecraft minecraft) {
        if (!loopEnabled || minecraft == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        try {
            clearMatching(minecraft, loopTarget, loopIncludePlayers);
        } catch (Throwable throwable) {
            logWarn("Loop clear failed: " + throwable.getMessage());
        }
    }

    public static String currentEntityCatalog() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return "实体目录不可用：客户端世界不存在。";
        }
        try {
            if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
                MinecraftServer server = minecraft.getSingleplayerServer();
                ServerLevel serverLevel = server.getLevel(minecraft.level.dimension());
                if (serverLevel != null) {
                    if (server.isSameThread()) {
                        return buildEntityCatalog(serverLevel.getAllEntities(), "serverlevel:" + minecraft.level.dimension().location());
                    }
                    java.util.function.Supplier<String> supplier = () -> buildEntityCatalog(serverLevel.getAllEntities(),
                            "serverlevel:" + minecraft.level.dimension().location());
                    return server.submit(supplier).join();
                }
            }
        } catch (Throwable throwable) {
            logWarn("Failed to build server entity catalog, fallback to client level: " + throwable.getMessage());
        }
        return buildEntityCatalog(minecraft.level.entitiesForRendering(), "clientlevel:" + minecraft.level.dimension().location());
    }

    private static String buildEntityCatalog(Iterable<Entity> entities, String source) {
        Map<String, CatalogEntry> entries = new LinkedHashMap<>();
        int total = 0;
        int skippedPlayers = 0;
        for (Entity entity : entities) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            total++;
            if (entity instanceof Player) {
                skippedPlayers++;
                continue;
            }
            ResourceLocation key = EntityType.getKey(entity.getType());
            String id = key == null ? entity.getType().toString() : key.toString();
            String path = key == null ? id : key.getPath();
            CatalogEntry entry = entries.computeIfAbsent(id, ignored -> new CatalogEntry(id, path,
                    safeDescriptionId(entity), safeEntityName(entity), entity.getClass().getName()));
            entry.count++;
        }

        StringBuilder catalog = new StringBuilder();
        catalog.append("source=").append(source)
                .append("；totalLoaded=").append(total)
                .append("；nonPlayerTypes=").append(entries.size())
                .append("；skippedPlayers=").append(skippedPlayers)
                .append('\n');
        for (CatalogEntry entry : entries.values()) {
            catalog.append("- id=").append(entry.id)
                    .append("；path=").append(entry.path)
                    .append("；count=").append(entry.count)
                    .append("；descriptionId=").append(entry.descriptionId)
                    .append("；name=").append(entry.name)
                    .append("；class=").append(entry.className)
                    .append('\n');
            if (catalog.length() > 16000) {
                catalog.append("...实体目录过长，已截断。\n");
                break;
            }
        }
        if (entries.isEmpty()) {
            catalog.append("- 当前没有可清除的非玩家实体。\n");
        }
        return catalog.toString();
    }

    private static String safeDescriptionId(Entity entity) {
        try {
            return entity.getType().getDescriptionId();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeEntityName(Entity entity) {
        try {
            return entity.getName().getString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static final class CatalogEntry {
        private final String id;
        private final String path;
        private final String descriptionId;
        private final String name;
        private final String className;
        private int count;

        private CatalogEntry(String id, String path, String descriptionId, String name, String className) {
            this.id = id;
            this.path = path;
            this.descriptionId = descriptionId;
            this.name = name;
            this.className = className;
        }
    }

    private static ClearStats clearMatching(Minecraft minecraft, String target, boolean includePlayers) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            logStep("clear", "skip: client level is null；target=" + target);
            return new ClearStats(target, 0, 0, 0, 0, 0);
        }
        List<Entity> snapshot = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            snapshot.add(entity);
        }

        String resolvedTarget = resolveCrosshairTarget(minecraft, target);
        logStep("clear", "begin target=" + resolvedTarget + "；includePlayers=" + includePlayers
                + "；clientSnapshot=" + snapshot.size());
        int seen = 0;
        int removed = 0;
        int serverRemoved = 0;
        int skippedPlayers = 0;
        int errors = 0;
        for (Entity entity : snapshot) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            if (entity == minecraft.player || (minecraft.player != null && entity.getUUID().equals(minecraft.player.getUUID()))) {
                skippedPlayers++;
                logStep("clear.skip", "local player: " + describeEntity(entity));
                continue;
            }
            if (entity instanceof Player && !includePlayers) {
                skippedPlayers++;
                logStep("clear.skip", "player excluded: " + describeEntity(entity));
                continue;
            }
            if (!matchesTarget(entity, resolvedTarget)) {
                continue;
            }
            seen++;
            int id = entity.getId();
            UUID uuid = entity.getUUID();
            logStep("clear.match", "matched " + describeEntity(entity) + "；target=" + resolvedTarget);
            try {
                if (removeFromIntegratedServer(minecraft, id, uuid)) {
                    serverRemoved++;
                }
                fangnaiRemove(entity);
                boolean clientGone = level.getEntity(id) == null;
                boolean removedFlag = entity.isRemoved();
                logStep("clear.done", "client cleanup id=" + id + "；uuid=" + uuid
                        + "；lookupGone=" + clientGone + "；removedFlag=" + removedFlag);
                removed++;
            } catch (Throwable throwable) {
                errors++;
                logWarn("Failed to clear entity " + describeEntity(entity) + ": " + throwable.getMessage());
            }
        }
        logStep("clear", "end target=" + resolvedTarget + "；seen=" + seen + "；clientRemoved=" + removed
                + "；serverRemoved=" + serverRemoved + "；skippedPlayers=" + skippedPlayers + "；errors=" + errors);
        return new ClearStats(resolvedTarget, seen, removed, serverRemoved, skippedPlayers, errors);
    }

    private static String resolveCrosshairTarget(Minecraft minecraft, String target) {
        if (!isCrosshairTarget(target)) {
            return target;
        }
        Entity picked = EntityBoxPicker.pick(minecraft, CROSSHAIR_PICK_DISTANCE);
        if (picked == null) {
            return "none";
        }
        ResourceLocation key = EntityType.getKey(picked.getType());
        return key == null ? picked.getType().toString() : key.toString();
    }

    private static boolean matchesTarget(Entity entity, String target) {
        if (target == null || target.isBlank() || "all".equals(target)) {
            return true;
        }
        if ("none".equals(target)) {
            return false;
        }
        if ("players".equals(target) || "player".equals(target)) {
            return entity instanceof Player;
        }
        if ("sheep".equals(target) || "minecraft:sheep".equals(target) || "羊".equals(target)) {
            return entity.getType() == EntityType.SHEEP || entity instanceof Sheep;
        }
        ResourceLocation key = EntityType.getKey(entity.getType());
        if (key != null) {
            String fullId = key.toString().toLowerCase(Locale.ROOT);
            String path = key.getPath().toLowerCase(Locale.ROOT);
            if (fullId.equals(target) || path.equals(target) || fullId.contains(target) || path.contains(target)) {
                return true;
            }
        }
        String descriptionId = safeDescriptionId(entity).toLowerCase(Locale.ROOT);
        String name = safeEntityName(entity).toLowerCase(Locale.ROOT);
        String className = entity.getClass().getName().toLowerCase(Locale.ROOT);
        return descriptionId.contains(target) || name.contains(target) || className.contains(target)
                || entity.getType().toString().toLowerCase(Locale.ROOT).contains(target);
    }

    private static String normalizeTarget(String target) {
        String normalized = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replace('：', ':');
        if (normalized.isBlank() || "全部".equals(normalized) || "所有".equals(normalized) || "实体".equals(normalized)
                || "all".equals(normalized) || "everything".equals(normalized) || "non_players".equals(normalized)) {
            return "all";
        }
        if (normalized.contains("羊") || "sheep".equals(normalized) || "minecraft:sheep".equals(normalized)) {
            return "sheep";
        }
        if (normalized.contains("准星") || normalized.contains("目标") || "crosshair".equals(normalized)) {
            return "crosshair";
        }
        if (normalized.contains("玩家") || "player".equals(normalized) || "players".equals(normalized)) {
            return "players";
        }
        return normalized;
    }

    private static boolean isCrosshairTarget(String target) {
        return "crosshair".equals(target) || "target".equals(target) || "目标".equals(target) || "准星".equals(target);
    }

    private record ClearStats(String target, int seen, int removed, int serverRemoved, int skippedPlayers, int errors) {
        private String summary() {
            return "target=" + target + "；seen=" + seen + "；clientRemoved=" + removed
                    + "；serverRemoved=" + serverRemoved + "；skippedPlayers=" + skippedPlayers + "；errors=" + errors;
        }
    }

    // 列表清除肘击！
    private static void fangnaiRemove(Entity entity) {
        if (entity == null)
            return;
        try {
            Minecraft.getInstance().gui.getBossOverlay().reset();
            logStep("boss", "reset client boss overlay");
        } catch (Exception exception) {
            logStep("boss", "skip boss overlay reset: " + exception.getMessage());
        }
        int entityId = entity.getId();
        UUID entityUUID = entity.getUUID();
        logStep("remove", "begin cleanup side=" + (entity.level().isClientSide ? "client" : "server") + "；" + describeEntity(entity));

        if (entity.level().isClientSide && entity.level() instanceof ClientLevel clientLevel) {
            removeFromClientLevelByCallback(clientLevel, entity, entityId);
        }

        if (!entity.level().isClientSide && entity.level() instanceof ServerLevel serverLevel) {
            removeFromServerLevelByCallback(serverLevel, entity, entityId);
            if (!removeFromChunkMap(serverLevel, entityId)) {
                logWarn("Failed to remove from ChunkMap: entity=" + entityId);
            }
            if (!removeFromServerLevelLists(serverLevel, entity)) {
                logWarn("Failed to remove from ServerLevel lists: entity=" + entityId);
            }
            if (!removeFromEntityLookup(serverLevel, entityId, entityUUID)) {
                logWarn("Failed to remove from EntityLookup: entity=" + entityId);
            }
            if (!removeFromKnownUuids(serverLevel, entityUUID)) {
                logWarn("Failed to remove from knownUuids: entity=" + entityId);
            }
            if (!removeFromEntityTickList(serverLevel, entityId)) {
                logWarn("Failed to remove from EntityTickList: entity=" + entityId);
            }
            if (!removeFromClassInstanceMultiMap(serverLevel, entity, entityId, entityUUID)) {
                logWarn("Failed to remove from ClassInstanceMultiMap: entity=" + entityId);
            }
        }

        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.level != null) {
                if (!removeFromClientContainers(minecraft.level, entity, entityId, entityUUID)) {
                    logWarn("Failed to remove from client containers: entity=" + entityId);
                }
            }
        } catch (Exception e) {
            logStep("client", "skip client container cleanup: " + e.getMessage());
        }

        int mockId = -300000 - new java.util.Random().nextInt(200000);
        setIntValueAuto(entity, mockId, "f_19774_", "id");
        logStep("remove", "end cleanup originalId=" + entityId + "；mockId=" + mockId + "；uuid=" + entityUUID
                + "；removedFlag=" + entity.isRemoved());
    }

    private static boolean removeFromClientLevelByCallback(ClientLevel clientLevel, Entity entity, int entityId) {
        boolean before = false;
        boolean after = false;
        try {
            before = clientLevel.getEntity(entityId) != null;
            logStep("client.callback", "before removeEntity id=" + entityId + "；lookupPresent=" + before
                    + "；removedFlag=" + entity.isRemoved());
            clientLevel.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
            after = clientLevel.getEntity(entityId) != null;
            logStep("client.callback", "after ClientLevel.removeEntity id=" + entityId + "；lookupPresent=" + after
                    + "；removedFlag=" + entity.isRemoved());
            if (after && !entity.isRemoved()) {
                entity.remove(Entity.RemovalReason.DISCARDED);
                after = clientLevel.getEntity(entityId) != null;
                logStep("client.callback", "after entity.remove fallback id=" + entityId + "；lookupPresent=" + after
                        + "；removedFlag=" + entity.isRemoved());
            }
            return before && !after;
        } catch (Throwable throwable) {
            logWarn("Client callback removal failed for " + describeEntity(entity) + ": " + throwable.getMessage());
            return before && !after;
        }
    }

    private static boolean removeFromServerLevelByCallback(ServerLevel serverLevel, Entity entity, int entityId) {
        boolean before = false;
        boolean after = false;
        try {
            before = serverLevel.getEntity(entityId) != null;
            logStep("server.callback", "before entity.remove id=" + entityId + "；lookupPresent=" + before
                    + "；removedFlag=" + entity.isRemoved());
            entity.remove(Entity.RemovalReason.DISCARDED);
            after = serverLevel.getEntity(entityId) != null;
            logStep("server.callback", "after entity.remove id=" + entityId + "；lookupPresent=" + after
                    + "；removedFlag=" + entity.isRemoved());
            return before && !after;
        } catch (Throwable throwable) {
            logWarn("Server callback removal failed for " + describeEntity(entity) + ": " + throwable.getMessage());
            return before && !after;
        }
    }

    private static boolean removeFromIntegratedServer(Minecraft minecraft, int entityId, UUID entityUUID) {
        if (minecraft == null || minecraft.level == null) {
            logStep("integrated", "skip: minecraft/client level missing for id=" + entityId);
            return false;
        }
        if (!minecraft.hasSingleplayerServer() || minecraft.getSingleplayerServer() == null) {
            logStep("integrated", "skip: no integrated server for client entity id=" + entityId);
            return false;
        }
        ResourceKey<Level> dimension = minecraft.level.dimension();
        MinecraftServer server = minecraft.getSingleplayerServer();
        ServerLevel serverLevel = server.getLevel(dimension);
        if (serverLevel == null) {
            logStep("integrated", "skip: server level missing for dimension=" + dimension.location());
            return false;
        }
        Entity serverEntity = serverLevel.getEntity(entityId);
        if (serverEntity == null && entityUUID != null) {
            serverEntity = serverLevel.getEntity(entityUUID);
        }
        if (serverEntity == null) {
            logStep("integrated", "skip: no server entity for id=" + entityId + "；uuid=" + entityUUID);
            return false;
        }
        Entity target = serverEntity;
        Runnable removeTask = () -> fangnaiRemove(target);
        logStep("integrated", "remove server entity " + describeEntity(target) + "；sameThread=" + server.isSameThread());
        try {
            if (server.isSameThread()) {
                removeTask.run();
            } else {
                server.submit(removeTask).join();
            }
            boolean stillPresent = serverLevel.getEntity(entityId) != null
                    || (entityUUID != null && serverLevel.getEntity(entityUUID) != null);
            logStep("integrated", "server removal finished id=" + entityId + "；uuid=" + entityUUID
                    + "；stillPresent=" + stillPresent);
            return !stillPresent;
        } catch (Throwable throwable) {
            logWarn("Integrated server removal failed for id=" + entityId + "；uuid=" + entityUUID + ": " + throwable.getMessage());
            return false;
        }
    }

    // 从ChunkMap移除所有Int2ObjectMap容器
    private static boolean removeFromChunkMap(ServerLevel serverLevel, int entityId) {
        try {
            Field chunkSourceField = findFieldByType(ServerLevel.class, "net.minecraft.server.level.ServerChunkCache");
            if (chunkSourceField == null)
                return false;

            chunkSourceField.setAccessible(true);
            Object chunkSource = chunkSourceField.get(serverLevel);
            if (chunkSource == null)
                return false;

            Field chunkMapField = findFieldByType(chunkSource.getClass(), "net.minecraft.server.level.ChunkMap");
            if (chunkMapField == null)
                return false;

            chunkMapField.setAccessible(true);
            Object chunkMap = chunkMapField.get(chunkSource);
            if (chunkMap == null)
                return false;

            boolean success = false;
            for (Field field : chunkMap.getClass().getDeclaredFields()) {
                if (!Int2ObjectMap.class.isAssignableFrom(field.getType()))
                    continue;
                if (Modifier.isStatic(field.getModifiers()))
                    continue;

                field.setAccessible(true);
                Int2ObjectMap<?> map = (Int2ObjectMap<?>) field.get(chunkMap);
                if (map != null && map.containsKey(entityId)) {
                    map.remove(entityId);
                    success = true;
                }
            }

            return success;
        } catch (Exception e) {
            logWarn("Failed to remove from ChunkMap: " + e.getMessage());
            return false;
        }
    }

    // 从ServerLevel的各种列表移除
    private static boolean removeFromServerLevelLists(ServerLevel serverLevel, Entity entity) {
        boolean success = false;
        try {
            if (entity instanceof ServerPlayer) {
                for (Field f : ServerLevel.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object value = f.get(serverLevel);
                        if (value instanceof List) {
                            List<?> list = (List<?>) value;
                            if (!list.isEmpty() && list.get(0) instanceof ServerPlayer) {
                                success = list.remove(entity);
                                break;
                            }
                        }
                    }
                }
            }

            if (entity instanceof Mob) {
                for (Field f : ServerLevel.class.getDeclaredFields()) {
                    if (Set.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object value = f.get(serverLevel);
                        if (value instanceof Set) {
                            String fieldName = f.getName();
                            if (fieldName.contains("navigating") || fieldName.contains("mob")) {
                                Set<?> set = (Set<?>) value;
                                success = set.remove(entity) || success;
                                break;
                            }
                        }
                    }
                }
            }

            if (entity.isMultipartEntity()) {
                for (Field f : ServerLevel.class.getDeclaredFields()) {
                    if (Int2ObjectMap.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object value = f.get(serverLevel);
                        if (value instanceof Int2ObjectMap) {
                            String fieldName = f.getName();
                            if (fieldName.contains("dragon") || fieldName.contains("part")) {
                                Int2ObjectMap<?> map = (Int2ObjectMap<?>) value;
                                for (PartEntity<?> part : entity.getParts()) {
                                    if (map.remove(part.getId()) != null) {
                                        success = true;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }
            return success;
        } catch (Exception e) {
            logWarn("Failed to remove from ServerLevel lists: " + e.getMessage());
            return false;
        }
    }

    // 从EntityLookup移除
    private static boolean removeFromEntityLookup(ServerLevel serverLevel, int entityId, UUID entityUUID) {
        Object pesm = persistentEntitySectionManager(serverLevel, "server.lookup");
        if (pesm == null) {
            return false;
        }
        return removeFromEntityLookupObject(pesm, entityId, entityUUID, "server.lookup");
    }

    private static boolean removeFromEntityLookupObject(Object entityManager, int entityId, UUID entityUUID, String label) {
        boolean success = false;
        try {
            Field visibleStorageField = findFieldByType(entityManager.getClass(),
                    "net.minecraft.world.level.entity.EntityLookup");
            if (visibleStorageField == null) {
                logStep(label, "EntityLookup field not found on " + entityManager.getClass().getName());
                return false;
            }
            visibleStorageField.setAccessible(true);
            Object entityLookup = visibleStorageField.get(entityManager);
            if (entityLookup == null) {
                logStep(label, "EntityLookup is null");
                return false;
            }

            Field byIdField = findFieldByType(entityLookup.getClass(),
                    "it.unimi.dsi.fastutil.ints.Int2ObjectMap");
            if (byIdField != null) {
                byIdField.setAccessible(true);
                Int2ObjectMap<?> byId = (Int2ObjectMap<?>) byIdField.get(entityLookup);
                if (byId != null) {
                    int before = byId.size();
                    java.util.Iterator<? extends Int2ObjectMap.Entry<?>> iterator = byId.int2ObjectEntrySet()
                            .iterator();
                    while (iterator.hasNext()) {
                        Int2ObjectMap.Entry<?> entry = iterator.next();
                        Object value = entry.getValue();
                        UUID valueUUID = uuidOf(value);
                        if ((entityUUID != null && entityUUID.equals(valueUUID)) || entry.getIntKey() == entityId) {
                            logStep(label, "remove byId entry key=" + entry.getIntKey() + "；value=" + describeObject(value));
                            iterator.remove();
                            success = true;
                        }
                    }
                    try {
                        if (byId.remove(entityId) != null) {
                            logStep(label, "remove byId direct key=" + entityId);
                            success = true;
                        }
                    } catch (Throwable throwable) {
                        logStep(label, "byId direct remove failed: " + throwable.getMessage());
                    }
                    logStep(label, "byId size " + before + " -> " + byId.size());
                }
            } else {
                logStep(label, "byId Int2ObjectMap field not found");
            }

            Field byUuidField = findFieldByType(entityLookup.getClass(), "java.util.Map");
            if (byUuidField != null) {
                byUuidField.setAccessible(true);
                Map<?, ?> byUuid = (Map<?, ?>) byUuidField.get(entityLookup);
                if (byUuid != null) {
                    int before = byUuid.size();
                    java.util.Iterator<? extends Map.Entry<?, ?>> iterator = byUuid.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<?, ?> entry = iterator.next();
                        Object value = entry.getValue();
                        UUID valueUUID = uuidOf(value);
                        if ((entityUUID != null && (entityUUID.equals(valueUUID) || entityUUID.equals(entry.getKey())))) {
                            logStep(label, "remove byUuid entry key=" + entry.getKey() + "；value=" + describeObject(value));
                            iterator.remove();
                            success = true;
                        }
                    }
                    try {
                        if (entityUUID != null && byUuid.remove(entityUUID) != null) {
                            logStep(label, "remove byUuid direct key=" + entityUUID);
                            success = true;
                        }
                    } catch (Throwable throwable) {
                        logStep(label, "byUuid direct remove failed: " + throwable.getMessage());
                    }
                    logStep(label, "byUuid size " + before + " -> " + byUuid.size());
                }
            } else {
                logStep(label, "byUuid Map field not found");
            }
            return success;
        } catch (Exception e) {
            logWarn(label + " failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean removeFromKnownUuidsObject(Object entityManager, UUID entityUUID, String label) {
        if (entityUUID == null) {
            logStep(label, "skip: uuid is null");
            return false;
        }
        try {
            Field knownUuidsField = findFieldByType(entityManager.getClass(), "java.util.Set");
            if (knownUuidsField == null) {
                logStep(label, "knownUuids Set field not found");
                return false;
            }
            knownUuidsField.setAccessible(true);
            Set<?> knownUuids = (Set<?>) knownUuidsField.get(entityManager);
            if (knownUuids == null) {
                logStep(label, "knownUuids is null");
                return false;
            }
            int before = knownUuids.size();
            boolean removed = knownUuids.remove(entityUUID);
            logStep(label, "knownUuids size " + before + " -> " + knownUuids.size()
                    + "；removed=" + removed + "；uuid=" + entityUUID);
            return removed;
        } catch (Exception e) {
            logWarn(label + " failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean removeFromKnownUuids(ServerLevel serverLevel, UUID entityUUID) {
        Object pesm = persistentEntitySectionManager(serverLevel, "server.knownUuids");
        if (pesm == null) {
            return false;
        }
        return removeFromKnownUuidsObject(pesm, entityUUID, "server.knownUuids");
    }

    // 从EntityTickList移除
    private static boolean removeFromEntityTickList(ServerLevel serverLevel, int entityId) {
        Object entityTickList = fieldValueByType(serverLevel, "net.minecraft.world.level.entity.EntityTickList", "server.tickList");
        if (entityTickList == null) {
            return false;
        }
        return removeFromEntityTickListObject(entityTickList, entityId, null, "server.tickList");
    }

    private static boolean removeFromEntityTickListObject(Object entityTickList, int entityId, UUID entityUUID, String label) {
        try {
            Field activeField = null;
            Field passiveField = null;
            Field iteratedField = null;

            for (Field f : entityTickList.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if ("active".equals(f.getName())) {
                    activeField = f;
                } else if ("passive".equals(f.getName())) {
                    passiveField = f;
                } else if ("iterated".equals(f.getName())) {
                    iteratedField = f;
                }
            }

            if (activeField == null || passiveField == null) {
                logStep(label, "active/passive maps not found");
                return false;
            }

            Int2ObjectMap<Entity> active = (Int2ObjectMap<Entity>) activeField.get(entityTickList);
            Int2ObjectMap<Entity> passive = (Int2ObjectMap<Entity>) passiveField.get(entityTickList);
            Int2ObjectMap<Entity> iterated = iteratedField != null
                    ? (Int2ObjectMap<Entity>) iteratedField.get(entityTickList)
                    : null;

            if (active == null || passive == null) {
                logStep(label, "active/passive map is null");
                return false;
            }

            if (iterated == active) {
                logStep(label, "active map is being iterated; swapping active/passive before removal");
                passive.clear();
                for (var entry : active.int2ObjectEntrySet()) {
                    passive.put(entry.getIntKey(), entry.getValue());
                }
                activeField.set(entityTickList, passive);
                passiveField.set(entityTickList, active);
                Int2ObjectMap<Entity> temp = active;
                active = passive;
                passive = temp;
            }

            boolean success = false;
            int before = active.size();
            java.util.Iterator<? extends Int2ObjectMap.Entry<Entity>> iterator = active.int2ObjectEntrySet().iterator();
            while (iterator.hasNext()) {
                Int2ObjectMap.Entry<Entity> entry = iterator.next();
                Entity value = entry.getValue();
                UUID valueUUID = uuidOf(value);
                if (entry.getIntKey() == entityId || (entityUUID != null && entityUUID.equals(valueUUID))) {
                    logStep(label, "remove active tick entry key=" + entry.getIntKey() + "；value=" + describeObject(value));
                    iterator.remove();
                    success = true;
                }
            }
            if (active.remove(entityId) != null) {
                logStep(label, "remove active tick direct key=" + entityId);
                success = true;
            }
            logStep(label, "active size " + before + " -> " + active.size());
            return success;
        } catch (Exception e) {
            logWarn(label + " failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean cleanClassInstanceMultiMap(Object classInstanceMultiMap, Entity entity, int entityId,
            UUID entityUUID) {
        if (classInstanceMultiMap == null)
            return false;
        boolean success = false;

        // 1. 尝试直接作为 Collection 进行 remove 喵！
        try {
            if (classInstanceMultiMap instanceof java.util.Collection<?>) {
                java.util.Collection<?> col = (java.util.Collection<?>) classInstanceMultiMap;
                java.util.Iterator<?> it = col.iterator();
                while (it.hasNext()) {
                    Object obj = it.next();
                    if (obj instanceof Entity) {
                        Entity e = (Entity) obj;
                        if (e == entity || e.getId() == entityId || entityUUID.equals(e.getUUID())) {
                            it.remove();
                            success = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. 深度反射暴力爆破所有字段，对 Collection 和 Map 全维度强拆喵！
        try {
            for (Field field : classInstanceMultiMap.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()))
                    continue;
                field.setAccessible(true);
                Object fieldValue = field.get(classInstanceMultiMap);
                if (fieldValue == null)
                    continue;

                // 如果字段是 Collection (包括 allInstances)
                if (fieldValue instanceof java.util.Collection<?>) {
                    java.util.Collection<?> col = (java.util.Collection<?>) fieldValue;
                    java.util.Iterator<?> it = col.iterator();
                    while (it.hasNext()) {
                        Object obj = it.next();
                        if (obj instanceof Entity) {
                            Entity e = (Entity) obj;
                            if (e == entity || e.getId() == entityId || entityUUID.equals(e.getUUID())) {
                                it.remove();
                                success = true;
                            }
                        }
                    }
                }
                // 如果字段是 Map (包括 byClass)
                else if (fieldValue instanceof Map<?, ?>) {
                    Map<?, ?> map = (Map<?, ?>) fieldValue;
                    for (Object value : map.values()) {
                        if (value instanceof java.util.Collection<?>) {
                            java.util.Collection<?> innerCol = (java.util.Collection<?>) value;
                            java.util.Iterator<?> it = innerCol.iterator();
                            while (it.hasNext()) {
                                Object obj = it.next();
                                if (obj instanceof Entity) {
                                    Entity e = (Entity) obj;
                                    if (e == entity || e.getId() == entityId || entityUUID.equals(e.getUUID())) {
                                        it.remove();
                                        success = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logWarn("Failed in cleanClassInstanceMultiMap: " + t.getMessage());
        }
        return success;
    }

    private static boolean removeFromClassInstanceMultiMap(ServerLevel serverLevel, Entity entity, int entityId,
            UUID entityUUID) {
        Object pesm = persistentEntitySectionManager(serverLevel, "server.sections");
        if (pesm == null) {
            return false;
        }
        return removeFromSectionsObject(pesm, entity, entityId, entityUUID, "server.sections");
    }

    private static boolean removeFromSectionsObject(Object entityManager, Entity entity, int entityId, UUID entityUUID,
            String label) {
        boolean success = false;
        try {
            Field sectionStorageField = findFieldByType(entityManager.getClass(),
                    "net.minecraft.world.level.entity.EntitySectionStorage");
            if (sectionStorageField == null) {
                logStep(label, "EntitySectionStorage field not found on " + entityManager.getClass().getName());
                return false;
            }

            sectionStorageField.setAccessible(true);
            Object sectionStorage = sectionStorageField.get(entityManager);
            if (sectionStorage == null) {
                logStep(label, "EntitySectionStorage is null");
                return false;
            }

            Field sectionsField = findFieldByType(sectionStorage.getClass(),
                    "it.unimi.dsi.fastutil.longs.Long2ObjectMap");
            if (sectionsField == null) {
                logStep(label, "sections Long2ObjectMap field not found");
                return false;
            }

            sectionsField.setAccessible(true);
            Long2ObjectMap<?> sections = (Long2ObjectMap<?>) sectionsField.get(sectionStorage);
            if (sections == null) {
                logStep(label, "sections map is null");
                return false;
            }

            int sectionsBefore = sections.size();
            int touchedSections = 0;
            java.util.Iterator<? extends Long2ObjectMap.Entry<?>> sectionIterator = sections.long2ObjectEntrySet().iterator();
            while (sectionIterator.hasNext()) {
                Long2ObjectMap.Entry<?> sectionEntry = sectionIterator.next();
                Object section = sectionEntry.getValue();
                if (section == null) {
                    continue;
                }

                Field storageField = findFieldByType(section.getClass(), "net.minecraft.util.ClassInstanceMultiMap");
                if (storageField == null) {
                    logStep(label, "section " + sectionEntry.getLongKey() + " has no ClassInstanceMultiMap field");
                    continue;
                }

                storageField.setAccessible(true);
                Object classInstanceMultiMap = storageField.get(section);
                if (classInstanceMultiMap == null) {
                    continue;
                }

                if (cleanClassInstanceMultiMap(classInstanceMultiMap, entity, entityId, entityUUID)) {
                    touchedSections++;
                    success = true;
                    logStep(label, "removed from section=" + sectionEntry.getLongKey() + "；section=" + section.getClass().getSimpleName());
                }

                if (sectionIsEmpty(section)) {
                    logStep(label, "remove empty section=" + sectionEntry.getLongKey());
                    sectionIterator.remove();
                }
            }
            logStep(label, "sections size " + sectionsBefore + " -> " + sections.size()
                    + "；touchedSections=" + touchedSections);
            return success;
        } catch (Exception e) {
            logWarn(label + " failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean removeFromClientContainers(net.minecraft.client.multiplayer.ClientLevel clientLevel,
            Entity entity, int entityId, UUID entityUUID) {
        boolean success = false;
        try {
            for (Field f : clientLevel.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object value = f.get(clientLevel);

                if (value instanceof Set<?> set && "tickingEntities".equals(f.getName())) {
                    int before = set.size();
                    if (set.remove(entity)) {
                        success = true;
                    }
                    logStep("client.tickingSet", "tickingEntities size " + before + " -> " + set.size()
                            + "；removed=" + (before != set.size()));
                }
            }

            Object tesm = fieldValueByType(clientLevel,
                    "net.minecraft.world.level.entity.TransientEntitySectionManager", "client.manager");
            if (tesm != null) {
                success = removeFromEntityLookupObject(tesm, entityId, entityUUID, "client.lookup") || success;
                success = removeFromSectionsObject(tesm, entity, entityId, entityUUID, "client.sections") || success;
            }

            Object entityTickList = fieldValueByType(clientLevel,
                    "net.minecraft.world.level.entity.EntityTickList", "client.tickList");
            if (entityTickList != null) {
                success = removeFromEntityTickListObject(entityTickList, entityId, entityUUID, "client.tickList") || success;
            }

            return success;
        } catch (Exception e) {
            logWarn("Failed to remove from client containers: " + e.getMessage());
            return false;
        }
    }

    private static void logWarn(String message) {
        System.out.println("[FangnaiRemove] " + message);
    }

    private static void logStep(String step, String message) {
        System.out.println("[FangnaiRemove][" + step + "] " + message);
    }

    private static String describeEntity(Entity entity) {
        if (entity == null) {
            return "null";
        }
        ResourceLocation key = EntityType.getKey(entity.getType());
        return "id=" + entity.getId()
                + "；uuid=" + entity.getUUID()
                + "；type=" + (key == null ? entity.getType() : key)
                + "；class=" + entity.getClass().getName()
                + "；removed=" + entity.isRemoved();
    }

    private static String describeObject(Object value) {
        if (value instanceof Entity entity) {
            return describeEntity(entity);
        }
        return value == null ? "null" : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static UUID uuidOf(Object value) {
        if (value instanceof Entity entity) {
            return entity.getUUID();
        }
        return null;
    }

    private static Object persistentEntitySectionManager(ServerLevel serverLevel, String label) {
        return fieldValueByType(serverLevel, "net.minecraft.world.level.entity.PersistentEntitySectionManager", label);
    }

    private static Object fieldValueByType(Object target, String typeName, String label) {
        if (target == null) {
            logStep(label, "target is null for type " + typeName);
            return null;
        }
        try {
            Field field = findFieldByType(target.getClass(), typeName);
            if (field == null) {
                logStep(label, "field not found: " + typeName + " on " + target.getClass().getName());
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            logStep(label, "field " + field.getName() + " -> " + (value == null ? "null" : value.getClass().getName()));
            return value;
        } catch (Throwable throwable) {
            logWarn(label + " field read failed for " + typeName + ": " + throwable.getMessage());
            return null;
        }
    }

    private static boolean sectionIsEmpty(Object section) {
        try {
            Method method = section.getClass().getDeclaredMethod("isEmpty");
            method.setAccessible(true);
            Object result = method.invoke(section);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setIntValueAuto(Object target, int value, String... names) {
        if (target == null) {
            return;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                try {
                    Field field = type.getDeclaredField(name);
                    if (field.getType() == int.class || field.getType() == Integer.class) {
                        field.setAccessible(true);
                        field.setInt(target, value);
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    private static Field findFieldByType(Class<?> type, String typeName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (fieldType != null && (fieldType.getName().equals(typeName) || typeName.equals(fieldType.getCanonicalName()))) {
                    return field;
                }
            }
        }
        return null;
    }

}