# Note 
   Forked to no longer use the embedded database for simplicity.  Now stores events
   in an in-memory queue before sending to Loggly.

# Important 
   The appender will drop messages that results in a 400 Bad Request from the loggly http API. 

# Usage.

Usage is relatively straight forward.

    <appender name="loggly"
      class="com.spidertracks.loggly.LogglyAppender">
      <param name="logglyUrl" value="Your loggly url goes here" /> 
      <param name="proxyHost" value="A dns name or an ip address"/> <!-- Optional value -->
      <param name="proxyPort" value="The port number for the proxy"/> <!-- Optional value -->
      <!-- The maximum number of messages to upload in a single http POST -->
      <param name="batchSize" value="50"/>
      <param name="queueSize" value="5000"/>
      <layout class="org.apache.log4j.EnhancedPatternLayout">
        <!-- Pattern to upload.  Will use the pattern layout specified when uploading -->
        <param name="ConversionPattern" value="%d{ISO8601}{GMT}Z %5p [%t]  %m%n" />
      </layout>
    </appender>

or 

    log4j.appender.loggly=com.spidertracks.loggly.LogglyAppender
    log4j.appender.loggly.logglyUrl=https://logs.loggly.com/inputs/xxxxx-xxxx
    log4j.appender.loggly.proxyHost=example.com
    log4j.appender.loggly.proxyPort=8080
    log4j.appender.loggly.batchSize=50
    log4j.appender.loggly.queueSize=5000
    log4j.appender.loggly.layout=org.apache.log4j.EnhancedPatternLayout
    log4j.appender.loggly.layout.ConversionPattern=%d{ISO8601}{GMT}Z %5p [%t]  %m%n



# Architecture.

Modified to no longer uses the embedded HSQL db

1. Log4j appender writes an entry to a queue
2. An asynchronous reader thread reads the oldest entry from the queue and
uploads it to the configured url.

This supports retried delivery.  If the logger cannot contact Loggly,
     a log4j internal message will be logged, and message will queue locally.
     The sending thread will continue to attempt to connect until it succeeds.  
     Excepts when the request http status codes i 400 Bad Request - then the messages is dumped.
     ex. a too large message.


