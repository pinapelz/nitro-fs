package com.pinapelz;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import static spark.Spark.*;

public class Main
{
    private static final Dotenv dotenv = Dotenv.load();

    public static String readSetting(String parameter) {
        String value = System.getenv(parameter);
        if (value != null) return value;
        return dotenv.get(parameter);
    }

    public static void startBot(){
        String dbHost = readSetting("PGHOST");
        String dbUser = readSetting("PGUSER");
        String dbPass = readSetting("PGPASSWORD");
        String dbName = readSetting("PGDATABASE");
        JDABuilder.createDefault(readSetting("BOT_TOKEN"))
                .addEventListeners(new MessageListener(dbHost, dbUser, dbPass, dbName))
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .build();
    }

    public static void main(String[] args) throws Exception{
        startBot();
        get("/hello", (req, res) -> "Hello World");
    }


}
