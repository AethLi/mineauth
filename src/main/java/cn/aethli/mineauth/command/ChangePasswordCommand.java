package cn.aethli.mineauth.command;

import cn.aethli.mineauth.common.utils.DataUtils;
import cn.aethli.mineauth.entity.AuthPlayer;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.List;

import static cn.aethli.mineauth.common.utils.MessageUtils.msgToOnePlayerByI18n;

public class ChangePasswordCommand extends BaseCommand<AuthPlayer> {
  private static final List<String> parameters = new ArrayList<>();

  static {
    parameters.add("oldPassword");
    parameters.add("newPassword");
    parameters.add("confirm");
  }

  public ChangePasswordCommand() {
    super("changePassword", parameters);
  }

  @Override
  public int run(CommandContext<CommandSource> context) throws CommandSyntaxException {
    CommandSource source = context.getSource();
    PlayerEntity player = source.asPlayer();
    String oldPassword = StringArgumentType.getString(context, "oldPassword");
    String newPassword = StringArgumentType.getString(context, "newPassword");
    String confirm = StringArgumentType.getString(context, "confirm");
    if (!newPassword.equals(confirm)) {
      msgToOnePlayerByI18n(player, "password_confirm_error");
      return 0;
    } else {
      AuthPlayer authPlayer = new AuthPlayer();
      authPlayer.setUuid(player.getUniqueID().toString());
      // MD5 is enough
      String digestedPassword = DigestUtils.md5Hex(oldPassword);
      authPlayer.setPassword(digestedPassword);
      authPlayer = DataUtils.selectOne(authPlayer);
      if (authPlayer == null) {
        // todo say old password wrong
        return 0;
      }
      digestedPassword = DigestUtils.md5Hex(newPassword);
      authPlayer.setPassword(digestedPassword);
      DataUtils.updateById(authPlayer);
      return 1;
    }
  }
}
