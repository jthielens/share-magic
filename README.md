share-magic
===========

SecureTransport Login Agent that symlinks subscription folders to a common shared folder.

## Installation

1. Install the agent code file `symlink-agent.jar`, either by uploading directly into `$FILEDRIVEHOME/brules/local/agents/inprocess/jars`, or through the `Transaction Manager`➤`Install Agents` function in the SecureTransport web admin.
2. Install the agent rule package file `SymlinkAgent.xml`, either by uploading directly into `$FILEDRIVEHOME/brules/local/wptdocuments` or through the `Transaction Manager`➤`Packages`➤`New Package` button in the SecureTransport web admin.
3. Enable the rule package by adding the line `SymlinkAgent=enabled` to the file `$FILEDRIVEHOME/brules/local/wptdocuments/wptdocuments.conf` or by clicking [Enable SymlinkAgent](#) on the `Transaction Manager`➤`Packages` page in the SecureTransport web admin.
4. Configure the agent event logging by editing `$FILEDRIVEHOME/conf/tm-log4j.xml` to create a new `<logger>` for the login agent.  Initially, set the log `level` to `debug` to become familiar with the agent's behavior and to verify its operation.  Note that the default level for `com.axway` agents is `info`.  See the configuration fragment below for the precise syntax.
5. Stop and start the Transaction Manager to force the libraries and rule changes to load.

#### Log4j Configuration Fragment

```
<logger name="com.axway.jbt" additivity="false">
    <level value="debug" />
    <appender-ref ref="ServerLog" />
</logger>
```

## Creating a Symlink Shared Application

With the agent installed and configured, any Application can be designated to use an underlying symlinked shared folder.  The process is very simple.

1. First, create the shared folder location where you would like the subscription folders linked.  Any content uploaded by subscribers will be comingled in this shared folder, and all users will have visibility to all files.
2. Next, add the text `share=/path/to/folder`, all on one line, in the Description for the Application.  The `share=` note does not need to be the first or last line, but it must appear on a line by itself.
3. Finally, subscribe Accounts to the Application.  The subscription may not be at the root folder of the account (`/`), but there are no other restrictions.

After the Application and Account Subscription are configured as above, the appropriate symlinks will be created a user associated with the Account logs in.  If the configuration is subsequently updated, the collection of symlinked folders in the account will be adjusted to match its `share=` subscriptions.

If a symlinked folder is removed, the agent will attempt to also remove any empty enclosing folders.  This avoids any "dangling" folder residue if a subscription in a nested folder `/a/b` is created and later removed, as `/a` is also cleaned up.

If a new symlinked folder needs to be created and there is an existing non-empty folder already there, the existing folder is renamed with a `.n` suffix, where `n` is the smallest positive integer for which a file or directory does not already exist.

# Log Messages

While the subscriptions with `share=` in the notes are being collected for the login account:

Level | Code   | Description
----- | ------ | -----------
WARN  | SYM000 | subscription at / share=_target_ ignored
DEBUG | SYM001 | subscription at _folder_ share=_target_
WARN  | SYM002 | subscription at _folder_ share=_target_: target doesn't exist -- ignored
ERROR | SYM003 | subscription at _folder_: getApplication failed!

While the shared folders are being located in the home folder for the login account:

Level | Code   | Description
----- | ------ | -----------
DEBUG | SYM004 | shared folder at _file_

While the existing shared folders and the required shared folders are being reconciled:

Level | Code   | Description
----- | ------ | -----------
DEBUG | SYM005 | _link_ -> _target_ is correct
DEBUG | SYM006 | _link_ -> _target_ is incorrect and will be updated to _target_
WARN  | SYM007 | _link_ -> ? error resolving symlink: _error_
DEBUG | SYM008 | _link_ -> _target_ is missing and will be added

While recursively purging a _nearly_ empty folder (meaning it contains only the `.stfs` metadata subfolder) no longer needed after symlinks have been removed:

Level | Code   | Description
----- | ------ | -----------
ERROR | SYM009 | purge folder "_folder_" failed: _error_
DEBUG | SYM010 | purge folder "_folder_" succeeded

While deleting empty folders no longer needed after symlinks have been removed:

Level | Code   | Description
----- | ------ | -----------
DEBUG | SYM011 | delete empty folder "_folder_" succeeded
ERROR | SYM012 | delete empty folder "_folder_" failed: _error_

While removing symlinks no longer needed:

Level | Code   | Description
----- | ------ | -----------
DEBUG | SYM013 | delete symlink "_folder_" succeeded
ERROR | SYM014 | delete symlink "_folder_" failed: _error_

While renaming existing directories to make way for new symlinks:

Level | Code   | Description
----- | ------ | -----------
DEBUG | SYM015 | rename "_folder_" to "_folder.n_" succeeded
ERROR | SYM016 | rename "_folder_" to "_folder.n_" failed: _error_

While creating new symlinks:

Level | Code   | Description
----- | ------ | -----------
DEBUG | SYM017 | link _folder_ -> _target_ succeeded
ERROR | SYM018 | link _folder_ -> _target_ failed: _error_

