package io.mosire.iibot.app;

import io.mosire.iibot.app.bot.BotMain;

/** iiBotMosire 唯一可运行入口：默认启动 bot-first QQ 客户端。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        BotMain.run(args);
    }
}
