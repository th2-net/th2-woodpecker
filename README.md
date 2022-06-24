# th2-woodpecker (1.0.0)

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
