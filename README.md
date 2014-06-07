# jWormhole Server

This is a JVM- and SSH-based localhost tunnel tool.


## Differences from other localhost tunnel solutions

* There is no commercial SaaS offering based on this tool, so there is no pay-walled feature.
* This is JVM-based, so it can be embedded into any other JVM applications. (This is why I created
  jWormhole in the first place.)


## Features

* User authentication is done via sshd.
* Support the ability to specify custom host name.


## Requirements

* Java 8 (due to the use of Lambda expressions)
* Running sshd
* Servlet container


## Installation

* Compile with `mvn package`.
* Create a configuration file (see the next section).
* Run the .war file with a servlet container, such as
  [Jetty Runner](http://wiki.eclipse.org/Jetty/Howto/Using_Jetty_Runner).
* Set up a wildcard A record for the domain to be used.
* **Optional**: set up SSL if desired (through reverse proxy or servlet container)
* For installation of jWormhole client, see
  [jWormhole Client](https://github.com/vvasabi/jwormhole-client) repo.


## Configuration

Below is the default configuration. Create a file at $HOME/.jwormhole/server.properties with the
following content. Uncomment options that need to be overridden.

```
# Domain name
#jwormhole.server.domainNamePrefix =
#jwormhole.server.domainNameSuffix = .example.com

# Port to access controller; must not be within the host port range
#jwormhole.server.controllerPort = 12700

# Random host port range; must be >= 1024 and < 65535
#jwormhole.server.hostPortRangeStart = 20000
#jwormhole.server.hostPortRangeEnd = 30000

# Number of characters of generated host keys; must be > 0
# Note that this does not limit the number of characters that a custom host name can have.
#jwormhole.server.hostNameLength = 5

# Time to drop inactive hosts in seconds; must be > 0
#jwormhole.server.hostTimeout = 60

# Time to garbage collect inactive hosts; must be <= hostTimeout and > 0
#jwormhole.server.hostManagerGcInterval = 20

# Forward client IP
#jwormhole.server.ipForwarded = false

# User agents shouldn’t send the url fragment but what if it does?
#jwormhole.server.urlFragmentSent = true
```


## License

```
  Copyright 2014 Brad Chen

  Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```
