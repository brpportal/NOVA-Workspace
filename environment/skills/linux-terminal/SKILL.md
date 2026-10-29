---
name: linux-terminal
when_to_use: When a task needs a real shell, command-line tools such as git, Python or Node.js, apt packages, text processing, compiling, or running a script against files in the Spark workspace. Use Spark's built-in Ubuntu Linux environment and its linux_status, linux_run and linux_install_tool tools.
---

# Linux Terminal — Real Ubuntu Guest

Spark includes a real Ubuntu 24.04 ARM64 userland that runs on the phone
through PRoot. This is the exception to the general rule that Spark works
only through its workspace and browser tools: when this skill applies,
use the Linux tools below instead of claiming that no shell exists.

The guest is not a virtual machine and not a security sandbox. PRoot
translates system calls in user space, so performance is reduced and
some packages or binaries may not work. The guest has fake root only;
it has no host root, no systemd service manager, no kernel modules and
no access to other Android apps or to Spark's API keys and vault.

## First check: linux_status

Call `linux_status` before planning Linux work. It reports the real
install state, Ubuntu version, rootfs size and the latest tool probes.

- READY means the environment was installed and its self-test passed.
- NOT_INSTALLED means the user must open the drawer, choose Terminal,
  and tap Install Environment. Do not retry linux_run in a loop.
- ERROR means an install or self-test failed. Report the state plainly
  and suggest Repair or Reset on the Terminal screen.
- Tool status is based on actual version-check commands inside the
  guest. Never call a tool installed without a successful probe.

## Running commands: linux_run

Use `linux_run(command, timeout_seconds, cwd)` for a real bash command.
It returns the actual exit code, stdout and stderr. Read all three:
exit 0 with warnings is different from a failed command.

The default working directory is /workspace. That directory is Spark's
normal app workspace bind-mounted into the guest, so a file written to
/workspace inside Linux is a real workspace file and can be checked
with the normal file tools or seen in Files.

Other useful guest locations:

- /root is the guest home directory and persists inside the rootfs.
- /tmp is guest temporary storage inside the rootfs.
- /workspace is the preferred place for user-visible inputs and outputs.
- Packages installed with apt persist across app restarts.

Commands are non-interactive. Standard input is closed, so do not start
interactive editors, shells, REPLs or prompts and expect to answer them.
Use scripts, command arguments and non-interactive flags. For apt work,
prefer linux_install_tool for catalogued tools; any raw apt command
must be non-interactive.

Each linux_run call is a separate guest process. The terminal screen
keeps its own visible working directory, but an agent call starts in
/workspace unless cwd is supplied. Shell variables set in one call do
not persist into the next call. Combine dependent steps in one command
string when they must share a directory or environment.

## Approval rules

The approval policy is part of the tool contract:

- linux_status is never gated.
- Pure inspection through linux_run can run directly: listing files,
  pwd, cat/head/tail of ordinary text, grep without writing, disk and
  file inspection, uname, and recognized version probes such as
  git --version, python3 --version, node --version or command -v git.
- linux_install_tool always asks the user for approval.
- Any other linux_run command asks for approval: apt operations,
  creating or changing files, deleting, moving, chmod/chown, network
  downloads, compiling, running arbitrary scripts and chained commands
  that are not inspection-only.
- If the user denies approval, stop that action. Do not rephrase the
  same operation to evade the approval system.

Never put API keys, passwords or vault secrets in a Linux command. The
guest process is deliberately started without Spark's provider keys or
secret environment; do not defeat that separation.

## Installing tools: linux_install_tool

Use `linux_install_tool(tool)` for the curated catalog:

- git — installs git.
- python3 — installs python3.
- pip — installs python3-pip.
- nodejs — installs nodejs and npm.
- curl — installs curl.
- wget — installs wget.
- zip — installs zip and unzip.

The tool runs the real apt-get update and install flow, then probes the
installed program and reports its version. Long installs can take
several minutes. Use linux_run for a package outside the catalog only
after explaining that it is an apt system change and receiving the
approval the policy requests.

## Working method

1. Call linux_status.
2. Inspect before changing: list the relevant directory and read the
   relevant files first.
3. Prefer /workspace for files the user should keep or inspect.
4. Run the smallest command that proves the next step.
5. Verify the result with a separate inspection: check the exit code,
   list the output file, run the installed tool's version command, or
   read the produced file with the normal workspace tools.
6. Report the real exit status and the important output. If output was
   truncated, say so and inspect the specific part that matters.

Examples of good verification:

- After installing Python, run linux_status or a python3 version probe.
- After a script writes /workspace/result.txt, list it in the guest and
  read it through the normal file tool before presenting it as done.
- After cloning into /workspace, inspect the directory and git status
  instead of assuming the clone succeeded.

## Limits and failure handling

- ARM64 only: the bundled PRoot runtime and Ubuntu rootfs are arm64.
- PRoot overhead makes builds and package operations slower than native.
- Some binaries may fail because of ptrace restrictions, missing kernel
  features, device nodes, FUSE, raw sockets or service requirements.
- Background daemons and systemd units are not supported. A command
  must finish, time out or be stopped; do not claim a server is running
  after its linux_run process has exited.
- Long commands need an explicit timeout. Split very large work into
  stages and verify each stage instead of retrying one huge command.
- If apt reports a lock, network or package error, quote the relevant
  error and stop. Do not invent a successful install.
- If the rootfs is in ERROR state, the user's recovery routes are
  Repair (re-configure and self-test) or Reset (delete and reinstall)
  on the Terminal screen.

The user's own terminal on the Terminal screen is the same environment:
commands and packages are shared with the agent tools, and its history
and output are real process output.
