package com.example.voicedialauto.playreg;

/** Runs as a Shizuku-spawned process (shell UID). Used for experimental set-installer. */
interface IShellUserService {
    String runCommand(String command);
    void destroy();
}
