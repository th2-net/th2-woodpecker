# th2-woodpecker (1.0.0)

Library that serves as an API for the implementations of th2 load generation tool «Woodpecker».

## Configuration

*maxBatchSize* - maximum size of a produced batch (`100` by default)  
*minBatchesPerSecond* - how many times per second batches will be generated (`1` by default)  
*maxOutputQueueSize* - size of the output queue for async batch sending (`0` by default = sync sending)  
*generatorSettings* - message generator implementation settings

## Release Notes

### 1.0.0

+ pass load settings to `onStart` method (if any were specified)
+ call `onStart` before each step
+ call `onStop` method after each step or on forced stop
+ ability to load dictionary in generator (via `IMessageGeneratorConfig.readDictionary(alias: String)`)
+ ability to set how many times per second batches will be generated (via `minBatchesPerSecond` setting)

### 0.1.0

+ Changed `onBatch` function to make it possible to send in several pins
+ Fixed a bug with message filtering by `message_type`
+ Disable waiting for connection recovery when closing the `SubscribeMonitor`
