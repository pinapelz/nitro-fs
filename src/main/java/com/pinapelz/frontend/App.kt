package com.pinapelz.frontend

import io.javalin.Javalin
import io.javalin.http.staticfiles.Location
import com.pinapelz.Retriever
import com.pinapelz.FileSystem

fun startFrontend(retriever: Retriever, fileSystem: FileSystem) {
    val app = Javalin.create {
        it.staticFiles.add("/public", Location.CLASSPATH)
    }
    app.get("/fetch") { ctx ->
        val fileId = ctx.queryParam("fileId")
        val fileMetadata = fileSystem.getFileById(Integer.parseInt(fileId));
        print(fileMetadata[1])
        ctx.html(retriever.getFileUrl(fileMetadata[0], fileMetadata[1], fileMetadata[2]));


    }
    app.start(7070)
}