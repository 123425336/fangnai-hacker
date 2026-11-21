package com.fangnai.hacker;

import com.fangnai.hacker.client.ai.tool.ToolRegistry;
import com.fangnai.hacker.client.config.HackerClientConfig;
import com.fangnai.hacker.client.define.RuntimeNameMapper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(HackerMod.MOD_ID)
public class HackerMod {
    public static final String MOD_ID = "hacker";

    public HackerMod() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> HackerMod::initClient);
    }

    private static void initClient() {
        HackerClientConfig.load();
        ToolRegistry.get().reload();
        // 后台预先准备运行期名称映射和编译存根，避免第一次编译 AI 工具时卡顿
        RuntimeNameMapper.get().warmUpAsync();
    }
}
