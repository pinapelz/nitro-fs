package com.pinapelz;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import java.sql.SQLException;

public class MessageListener extends ListenerAdapter {

    private FileSystem fileSystem;


    public MessageListener(FileSystem fileSystem){
        this.fileSystem = fileSystem;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event)
    {
        if (event.getAuthor().isBot()) return;
        Message message = event.getMessage();
        String content = message.getContentRaw();

        if(!message.getAttachments().isEmpty()){
            DirectoryInfo dirInfo = parseDirectoryFromMessage(content);
            
            for(Message.Attachment attachment : message.getAttachments()){
                try {
                    fileSystem.createNewFile(
                        message.getChannelId(), 
                        message.getId(), 
                        dirInfo.directoryId,
                        dirInfo.description, 
                        attachment
                    );
                    message.addReaction(Emoji.fromUnicode("✅")).queue();
                    System.out.println("File uploaded to directory: " + dirInfo.path + " (" + attachment.getFileName() + ")");
                    
                } catch (Exception e) {
                    message.addReaction(Emoji.fromUnicode("❌")).queue();
                    System.err.println("Upload failed for " + attachment.getFileName() + ": " + e.getMessage());
                }
            }
        }

        if (content.equals("!ping"))
        {
            MessageChannel channel = event.getChannel();
            channel.sendMessage("Pong!").queue();
        }
    }

    private DirectoryInfo parseDirectoryFromMessage(String message) {
        if (message.contains(":")) {
            String[] parts = message.split(":", 2);
            String dirPath = parts[0].trim();
            String description = parts.length > 1 ? parts[1].trim() : "";
            int directoryId = findOrCreateDirectory(dirPath);
            return new DirectoryInfo(directoryId, description, dirPath);
        }
        
        return new DirectoryInfo(1, message, "root");
    }

    private int findOrCreateDirectory(String path) {
        try {
            return fileSystem.findOrCreateDirectory(path);
        } catch (SQLException e) {
            System.err.println("Directory creation failed for '" + path + "', using root: " + e.getMessage());
            return 1;
        }
    }

    private static class DirectoryInfo {
        int directoryId;
        String description;
        String path;
        
        DirectoryInfo(int directoryId, String description, String path) {
            this.directoryId = directoryId;
            this.description = description;
            this.path = path;
        }
    }

}
