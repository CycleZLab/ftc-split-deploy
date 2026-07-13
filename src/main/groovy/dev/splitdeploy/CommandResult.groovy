package dev.splitdeploy

import groovy.transform.Immutable

@Immutable
class CommandResult {
    int exitCode
    String output

    boolean isSuccess() {
        return exitCode == 0
    }
}
