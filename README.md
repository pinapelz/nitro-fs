# Nitro-FS (WIP)
> [!CAUTION]
> Do **NOT** use Discord as a substitute for cloud storage. All uploaded files are public and are at the mercy of Discord.
> Keep backups!
> 
> Only use this for files that you are OK with losing.

An alternative approach to storing files on Discord, specifically targeted at those with a Nitro membership.

Nitro-FS assumes that you have increased upload limit to Discord and thus does not chunk your file into parts.
This removes the hassle of splitting and merging lots of smaller files, or data loss if one particular chunk is lost.

- Uses a Bot to watch for attachments on a given server
  - Allows you to upload from any device that can access to Discord, no need to connect to Web/UI for this
- Postgres as the index
  - Doesn't store metadata in Discord messages to allow for faster retrieval

# Setup
1. Create `.env` based `.env.template` on
2. `WEBHOOKS_TXT` is a path to a text file containing webhooks, separated by newlines