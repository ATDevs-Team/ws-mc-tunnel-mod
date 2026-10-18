# ws-mc-tunnel-mod
The source code for the websocket TCP proxy Fabric mod used by ATHost for connecting you to Minecraft servers running on ATDevs Team servers.

# Modifying the Connection Details
If you need to for some reason modify the connection details within the mod itself, open ./src/main/java/org/atdevs/mctunnel/TunnelMod.java from the project root in a text/code editor.
Connection details start on line 24. 
Our defaults are these, changing the `websocket.url` or `websocket.token` properties WILL make you unable to authenticate with the ATHost server-side Minecraft TCP relay!:
```Java
                config.setProperty("listen.host", "127.0.0.1");
                config.setProperty("listen.port", "25565");
                config.setProperty(
                    "websocket.url",
                    "wss://play-atmc.atdevs.org/mc"
                );
                config.setProperty("websocket.token", "fd047bbe5ce1bcf85bb95981c8206c20cbf62a02e48840bd5861546794584310");
```
> [!IMPORTANT]
> That token is for the public instance. In this case it <ins>***is not a secret***<ins>.\
> Please <ins>**DO NOT**<ins> create an issue or pull request saying this exists.

> [!CAUTION]
> The public instance (or any instance) has no account-based security, as we can't implement Mojang's account authentication in our custom server.[^1]\
> We will implement our own security system before the public ATHost release of Minecraft hosting.[^2]


[^1]: We use a custom server written entirely in Rust by us that looks like a normal server to anything that connects,\
but internally is all our own code.
[^2]: The server will check your client's reported username in its database, and ask you to create an account if you don't have one,\
otherwise it'll ask you to send `!password <your-password-here>` to log in. Three failed attempts will cause a forced improper disconnect of the websocket.\
Per-account rate-limit is 30 attempts per hour and 2 per minute. Per-IP rate-limit is 15 per hour and 4 per minute.\
Rate limits apply only to failed authentication attempts.
