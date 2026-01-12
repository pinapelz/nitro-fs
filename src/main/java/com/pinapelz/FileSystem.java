package com.pinapelz;

import net.dv8tion.jda.api.entities.Message;

import java.sql.ResultSet;
import java.sql.SQLException;


public class FileSystem {
    private Database database;
    public FileSystem(String dbHost, String dbUser, String dbPass, String dbName){
        database = new Database(dbHost, dbUser, dbPass, dbName);
    }

    public DiscordFilePath getFileById(int fileId){
        String[] rawDiscordFilePath = database.getFileById(fileId);
        DiscordFilePath discPath = new DiscordFilePath();
        discPath.channelId = Long.parseLong(rawDiscordFilePath[0]);
        discPath.messageId = Long.parseLong(rawDiscordFilePath[1]);
        discPath.fileName = rawDiscordFilePath[2];
        return discPath;
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

    public ResultSet getFilesByDirectoryId(int directoryId, String search, String mimeTypeFilter, String sortBy) {
        return database.getFilesByDirectoryId(directoryId, search, mimeTypeFilter, sortBy);
    }

    public int findOrCreateDirectory(String path) throws SQLException {
        ResultSet rs = getAllDirectories();
        while (rs.next()) {
            if (path.equals(rs.getString("path"))) {
                int id = rs.getInt("directory_id");
                rs.close();
                return id;
            }
        }
        rs.close();

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

    public long createFilePartial(String channelId, String messageId, int directoryId, 
                                 String partName, int partNumber, long partSize, 
                                 String originalFilename, String description, String mimeType) throws SQLException {
        return database.recordFilePartial(channelId, messageId, directoryId, partName, 
                                        partNumber, partSize, originalFilename, description, mimeType);
    }

    public ResultSet getFilePartialsByOriginalFilename(String originalFilename, int directoryId) {
        return database.getFilePartialsByOriginalFilename(originalFilename, directoryId);
    }

    public ResultSet getGroupedPartials(int directoryId, String search) {
        return database.getUniqueOriginalFilesFromPartials(directoryId, search);
    }

    public boolean deleteFilePartials(String originalFilename, int directoryId) throws SQLException {
        return database.deleteFilePartials(originalFilename, directoryId);
    }

    public boolean checkPartialNameConstraint(String partName, int directoryId) throws SQLException {
        return database.checkPartialExists(partName, directoryId);
    }
}
