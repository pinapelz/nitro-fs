package com.pinapelz;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

import static com.pinapelz.frontend.AppKt.startFrontend;

public class Main
{
    private static final Dotenv dotenv = Dotenv.load();
    private static FileSystem fileSystem;

    public static String readSetting(String parameter) {
        String value = System.getenv(parameter);
        if (value != null) return value;
        return dotenv.get(parameter);
    }

    public static JDA startBot(){
        String dbHost = readSetting("PGHOST");
        String dbUser = readSetting("PGUSER");
        String dbPass = readSetting("PGPASSWORD");
        String dbName = readSetting("PGDATABASE");
        fileSystem = new FileSystem(dbHost, dbUser, dbPass, dbName);
        return JDABuilder.createDefault(readSetting("BOT_TOKEN"))
                .addEventListeners(new MessageListener(fileSystem))
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .build();
    }

    public static void main(String[] args) throws Exception{
        JDA jda = startBot();
        startFrontend(new Retriever(jda), fileSystem);
    }


}
