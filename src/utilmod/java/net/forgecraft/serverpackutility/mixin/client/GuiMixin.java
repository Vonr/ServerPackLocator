package net.forgecraft.serverpackutility.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.realmsclient.client.RealmsClient;
import net.forgecraft.serverpacklocator.ModAccessor;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Shadow
    private Minecraft minecraft;

    @WrapMethod(method = "buildInitialScreens")
    private Runnable serverpacklocator$buildInitialScreens(@Nullable GameLoadCookie cookie, Operation<Runnable> operation) {
        // parse server address
        var serverAddress = ServerAddress.parseString(ModAccessor.getQuickPlayServer());

        if ((cookie == null || !cookie.quickPlayData().isEnabled()) && !serverAddress.getHost().equals("server.invalid")) {
            RealmsClient realmsclient = RealmsClient.getOrCreate(minecraft);
            cookie = new GameLoadCookie(realmsclient, new GameConfig.QuickPlayData(
                null,
                new GameConfig.QuickPlayMultiplayerData(serverAddress.toString())
            ));
        }

        return operation.call(cookie);
    }
}
