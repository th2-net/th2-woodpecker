# th2-woodpecker (1.2.2)

Library that serves as an API for the implementations of th2 load generation tool «Woodpecker».

## Configuration

*tickRate* - how many times per second message batches will be generated (`10` by default)  
*maxBatchSize* - maximum size of a produced batch (`1000` by default)  
*maxOutputQueueSize* - size of the output queue for async batch sending (`0` by default = sync sending)  
*generatorSettings* - message generator implementation settings

## Load distribution

Following graph illustrates how load is distributed in time depending on its rate, `maxBatchSize`, and `tickRate` settings.

![](doc/img/load-distribution.svg "Load distribution")

## Release Notes

### 1.2.1

+ fixed load generation when no `tickRate` or `maxBatchSize` were provided in `Step` message

### 1.2.0

+ allow overriding `tickRate` and `maxBatchSize` for each load run (by specifying corresponding properties in `StartLoad` and `Step` messages)

### 1.1.0

+ Attach events to the specified parent event ID during generation (if any was provided in the initial request)

### 1.0.6

+ check that rate is higher or equal to `tickRate`

### 1.0.5

+ attach load settings to corresponding events

### 1.0.4

+ fix inability to pass empty settings to schedule request step

### 1.0.3

+ better errors messages in case of `onStart`, `onStop` method failures

### 1.0.2

+ better error messages in case of invalid load settings in request

### 1.0.1

+ ensure that ticks aren't executed at a higher rate, if a slow-generator suddenly speeds up, to avoid overshooting target rate

### 1.0.0

+ pass load settings to `onStart` method (if any were specified)
+ call `onStart` before each step
+ call `onStop` method after each step or on forced stop
+ ability to load dictionary in generator (via `IMessageGeneratorConfig.readDictionary(alias: String)`)
+ ability to set how many times per second batches will be generated (via `tickRate` setting)

### 0.1.0

+ Changed `onBatch` function to make it possible to send in several pins
+ Fixed a bug with message filtering by `message_type`
+ Disable waiting for connection recovery when closing the `SubscribeMonitor`
