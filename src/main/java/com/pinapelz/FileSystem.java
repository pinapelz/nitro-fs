package com.pinapelz;

import net.dv8tion.jda.api.entities.Message;

import java.sql.SQLException;

public class FileSystem {
    private Database database;
    public FileSystem(String dbHost, String dbUser, String dbPass, String dbName){
        database = new Database(dbHost, dbUser, dbPass, dbName);
    }

    public void createNewFile(String messageId, String channelId, String description, Message.Attachment attachment){
        int fileSize = attachment.getSize();
        String filename = attachment.getFileName();
        String mimeType = attachment.getContentType();
        try {
            database.recordFileMetadata(channelId, messageId, 1, filename, description, fileSize, mimeType );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
