package cn.aethli.mineauth.handler;

import cn.aethli.mineauth.command.account.*;
import cn.aethli.mineauth.common.model.PlayerPreparation;
import cn.aethli.mineauth.common.utils.I18nUtils;
import cn.aethli.mineauth.common.utils.MetadataUtils;
import cn.aethli.mineauth.config.MineauthConfig;
import cn.aethli.mineauth.entity.AuthPlayer;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.play.server.SDisconnectPacket;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static cn.aethli.mineauth.common.utils.DataUtils.initialInternalDatabase;
import static cn.aethli.mineauth.common.utils.MessageUtils.msgToOnePlayerByI18n;

public class AccountHandler {
  public static final String DEFAULT_H2_DATABASE_FILE_RESOURCE_PATH =
      "/assets/mineauth/initial/internalDatabase.mv.db";
  private static final List<String> allowCommands = new ArrayList<>();
  private static final Logger LOGGER = LogManager.getLogger();
  private static final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);
  private static final Map<String, AuthPlayer> AUTH_PLAYER_MAP = new ConcurrentHashMap<>();
  private static final Map<String, PlayerPreparation> PLAYER_PREPARATION_MAP =
      new ConcurrentHashMap<>();

  public AccountHandler() throws IOException {
    initialInternalDatabase(DEFAULT_H2_DATABASE_FILE_RESOURCE_PATH);
    MetadataUtils.initMetadata();
  }

  /**
   * auth player
   *
   * @param key player unique id
   * @param authPlayer @see cn.aethli.mineauth.entity.AuthPlayer
   */
  public static void addToAuthPlayerMap(String key, AuthPlayer authPlayer) {
    AUTH_PLAYER_MAP.put(key, authPlayer);
    // set back food level
    PlayerPreparation playerPreparation = PLAYER_PREPARATION_MAP.get(key);
    playerPreparation
        .getPlayerEntity()
        .getFoodStats()
        .setFoodLevel(playerPreparation.getFoodLevel());
    PLAYER_PREPARATION_MAP.remove(key);
  }

  private void handleLivingEvents(LivingEvent event) {
    if (event.getEntity() instanceof PlayerEntity
        && event.isCancelable()
        && PLAYER_PREPARATION_MAP.containsKey(event.getEntity().getUniqueID().toString())) {
      msgToOnePlayerByI18n((PlayerEntity) event.getEntity(), "welcome");
      event.setCanceled(true);
    }
  }

  /**
   * hold on player's position and rotation parameter, to cancel player's movement event and reset
   * to initial state
   *
   * @param event event
   */
  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onPlayerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
    PlayerEntity player = event.getPlayer();
    PlayerPreparation playerPreparation =
        new PlayerPreparation(
            player,
            player.getPositionVec(),
            player.rotationYaw,
            player.rotationPitch,
            player.getFoodStats().getFoodLevel());
    String playerId = player.getUniqueID().toString();
    AUTH_PLAYER_MAP.remove(playerId);
    PLAYER_PREPARATION_MAP.put(playerId, playerPreparation);
    msgToOnePlayerByI18n(player, "welcome");
    scheduler.schedule(
        () -> {
          if (PLAYER_PREPARATION_MAP.containsKey(playerId)) {
            PLAYER_PREPARATION_MAP.remove(playerId);
            AUTH_PLAYER_MAP.remove(playerId);
            ((ServerPlayerEntity) event.getPlayer())
                .connection.sendPacket(
                    new SDisconnectPacket(
                        new TranslationTextComponent(
                            I18nUtils.getTranslateContent("deny"),
                            MineauthConfig.MINEAUTH_CONFIG.delay.get())));
          }
        },
        MineauthConfig.MINEAUTH_CONFIG.delay.get(),
        TimeUnit.SECONDS);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onPlayerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event) {
    String uuid = event.getPlayer().getUniqueID().toString();
    AUTH_PLAYER_MAP.remove(uuid);
    PLAYER_PREPARATION_MAP.remove(uuid);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onLivingAttackEvent(LivingAttackEvent event) {
    handleLivingEvents(event);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onLivingDeathEvent(LivingDeathEvent event) {
    handleLivingEvents(event);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onLivingEntityUseItemEvent(LivingEntityUseItemEvent event) {
    handleLivingEvents(event);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onLivingHealEvent(LivingHealEvent event) {
    handleLivingEvents(event);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onLivingHurtEvent(LivingHurtEvent event) {
    handleLivingEvents(event);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onLivingSetTargetAttackEvent(LivingSetAttackTargetEvent event) {
    if (event.getTarget() instanceof PlayerEntity
        && PLAYER_PREPARATION_MAP.containsKey(event.getTarget().getUniqueID().toString())) {
      event.getEntityLiving().setRevengeTarget(null);
    }
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onPlayerEvent(PlayerEvent event) {
    PlayerEntity player = event.getPlayer();
    if (!AUTH_PLAYER_MAP.containsKey(player.getUniqueID().toString()) && event.isCancelable()) {
      event.setCanceled(true);
      msgToOnePlayerByI18n(player, "welcome");
    }
  }

  @SubscribeEvent
  public void onPlayerInteractEvent(PlayerInteractEvent event) {
    PlayerEntity player = event.getPlayer();
    if (!AUTH_PLAYER_MAP.containsKey(player.getUniqueID().toString())
        && event.getSide() == LogicalSide.SERVER) {
      event.setCanceled(true);
      msgToOnePlayerByI18n(player, "welcome");
    }
  }

  @SubscribeEvent
  public void onPlayerTickEvent(TickEvent.PlayerTickEvent event) {
    PlayerEntity player = event.player;
    if (!AUTH_PLAYER_MAP.containsKey(player.getUniqueID().toString())
        && event.side == LogicalSide.SERVER) {
      PlayerPreparation playerPreparation =
          PLAYER_PREPARATION_MAP.get(event.player.getUniqueID().toString());
      Vector3d vector3d = playerPreparation.getVector3d();
      ((ServerPlayerEntity) event.player)
          .connection.setPlayerLocation(
              vector3d.getX(),
              vector3d.getY(),
              vector3d.getZ(),
              playerPreparation.getRotationYaw(),
              playerPreparation.getRotationPitch());
    }
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onServerChatEvent(ServerChatEvent event) {
    PlayerEntity player = event.getPlayer();
    if (event.isCancelable() && !AUTH_PLAYER_MAP.containsKey(player.getUniqueID().toString())) {
      event.setCanceled(true);
      msgToOnePlayerByI18n(player, "welcome");
    }
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onItemTossEvent(ItemTossEvent event) {
    PlayerEntity player = event.getPlayer();
    if (event.isCancelable() && !AUTH_PLAYER_MAP.containsKey(player.getUniqueID().toString())) {
      // avoid toss item without login
      player.inventory.addItemStackToInventory(event.getEntityItem().getItem());
      event.setCanceled(true);
      msgToOnePlayerByI18n(player, "welcome");
    }
  }

  @SubscribeEvent
  public void onPlayerContainerEvent(PlayerContainerEvent event) {
    PlayerEntity player = event.getPlayer();
    if (event.isCancelable() && !AUTH_PLAYER_MAP.containsKey(player.getUniqueID().toString())) {
      event.setCanceled(true);
      msgToOnePlayerByI18n(player, "welcome");
    }
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onCommandEvent(CommandEvent event) throws CommandSyntaxException {
    String name = event.getParseResults().getContext().getNodes().get(0).getNode().getName();
    CommandSource source = event.getParseResults().getContext().getSource();
    if (source.getEntity() instanceof ServerPlayerEntity) {
      PlayerEntity player = source.asPlayer();
      if (!allowCommands.contains(name)
          && !AUTH_PLAYER_MAP.containsKey(player.getUniqueID().toString())
          && event.isCancelable()) {
        LOGGER.info(
            "Player {} tried to execute /{} without being logged in.",
            player.getName().getString(),
            name);
        event.setCanceled(true);
        msgToOnePlayerByI18n(player, "welcome");
      }
    }
  }

  @SubscribeEvent
  public void onRegisterCommandsEvent(RegisterCommandsEvent event) {
    event.getDispatcher().register(new LoginCommand().getBuilder());
    allowCommands.add(LoginCommand.command);
    if (MineauthConfig.MINEAUTH_CONFIG.enableRegister.get()) {
      event.getDispatcher().register(new RegisterCommand().getBuilder());
      allowCommands.add(RegisterCommand.command);
    }
    if (MineauthConfig.MINEAUTH_CONFIG.enableChangePassword.get()) {
      event.getDispatcher().register(new ChangePasswordCommand().getBuilder());
      allowCommands.add(ChangePasswordCommand.command);
    }
    event.getDispatcher().register(new RegisterHelpCommand().getBuilder());
    allowCommands.add(RegisterHelpCommand.command);
    event.getDispatcher().register(new LoginHelpCommand().getBuilder());
    allowCommands.add(LoginHelpCommand.command);
  }
}