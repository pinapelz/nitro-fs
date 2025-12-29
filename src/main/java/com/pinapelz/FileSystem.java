package com.pinapelz;

import net.dv8tion.jda.api.entities.Message;

import java.sql.ResultSet;
import java.sql.SQLException;

public class FileSystem {
    private Database database;
    public FileSystem(String dbHost, String dbUser, String dbPass, String dbName){
        database = new Database(dbHost, dbUser, dbPass, dbName);
    }

    public String[] getFileById(int fileId){
        return database.getFileById(fileId);
    }

    public void createNewFile(String channelId, String messageId, int directoryId, String description, Message.Attachment attachment){
        int fileSize = attachment.getSize();
        String filename = attachment.getFileName();
        String mimeType = attachment.getContentType();
        try {
            database.recordFileMetadata(channelId, messageId, directoryId, filename, description, fileSize, mimeType );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // Backward compatibility - defaults to root directory (ID 1)
    public void createNewFile(String channelId, String messageId, String description, Message.Attachment attachment){
        createNewFile(channelId, messageId, 1, description, attachment);
    }
    public ResultSet getFilesByDirectoryIdFiltered(int directoryId, String search, String mimeTypeFilter, String sortBy) {
        return database.getFilesByDirectoryId(directoryId, search, mimeTypeFilter, sortBy);
    }

    public int findOrCreateDirectory(String path) throws SQLException {
        // Try to find existing directory
        ResultSet rs = getAllDirectories();
        while (rs.next()) {
            if (path.equals(rs.getString("path"))) {
                int id = rs.getInt("directory_id");
                rs.close();
                return id;
            }
        }
        rs.close();

        // Create new directory if not found
        return createDirectory(path);
    }

    public ResultSet getAllDirectories() {
        return database.getAllDirectories();
    }

    public ResultSet getDirectoryById(int directoryId) {
        return database.getDirectoryById(directoryId);
    }

    public int createDirectory(String path) throws SQLException {
        return database.createDirectory(path);
    }

    public boolean deleteFile(int fileId) throws SQLException {
        return database.deleteFile(fileId);
    }

    public boolean deleteDirectory(int directoryId) throws SQLException {
        return database.deleteDirectory(directoryId);
    }
}
