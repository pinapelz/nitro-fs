package com.pinapelz;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

public class Retriever {

    private final JDA jda;

    public Retriever(JDA jda) {
        this.jda = jda;
    }

    public String getFileUrl(String channelId, String messageId, String fileName) {
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            throw new RuntimeException("Channel not found or deleted");
        }
        System.out.println(channelId + " " + messageId + fileName);

        Message message = channel.retrieveMessageById(messageId).complete();

        for (Message.Attachment file : message.getAttachments()) {
            if (file.getFileName().equals(fileName)) {
                return file.getProxyUrl();
            }
        }

        throw new RuntimeException("Matching attachment not found");
    }
}