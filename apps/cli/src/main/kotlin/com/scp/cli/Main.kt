package com.scp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.scp.config.SecureFiles

public fun main(args: Array<String>) {
    // Pin logback to <baseDir>/storage/logs before any logger initializes — otherwise logs go to
    // the working directory while the database goes to -Dscp.home, and the two silently diverge.
    SecureFiles.prepareLogging(baseDir())

    Scp()
        .subcommands(
            InitCommand(),
            CreateProjectCommand(),
            ListProjectsCommand(),
            UpdateCommand(),
            HydrateCommand(),
            SearchCommand(),
            SummaryCommand(),
            TimelineCommand(),
            DoctorCommand(),
        ).main(args)
}

internal class Scp : CliktCommand(name = "scp") {
    override fun run(): Unit = Unit
}
