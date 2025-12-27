package com.pinapelz.frontend

import io.javalin.Javalin

fun startFrontend() {
    val app = Javalin.create()
        .get("/") { ctx -> ctx.result("WIP. Not much here yet") }
        .start(7070)
}