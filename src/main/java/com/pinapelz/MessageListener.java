package com.pinapelz;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class MessageListener extends ListenerAdapter {

    private FileSystem fileSystem;

    public MessageListener(String dbHost, String dbUser, String dbPass, String dbName){
        fileSystem = new FileSystem(dbHost, dbUser, dbPass, dbName);

    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event)
    {
        if (event.getAuthor().isBot()) return;
        Message message = event.getMessage();
        String content = message.getContentRaw();

        System.out.println(message.getAttachments().get(0).getUrl());
        if(!message.getAttachments().isEmpty()){
            System.out.println("Attachment Received! Filing this away now...");
            for(Message.Attachment attachment : message.getAttachments()){
                fileSystem.createNewFile(message.getChannelId(), message.getChannelId(), content, attachment);
            }
        }

        if (content.equals("!ping"))
        {
            MessageChannel channel = event.getChannel();
            channel.sendMessage("Pong!").queue(); // Important to call .queue() on the RestAction returned by sendMessage(...)
        }
    }

}
