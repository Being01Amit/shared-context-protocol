package com.scp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

public fun main(args: Array<String>) {
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
