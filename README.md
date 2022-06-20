# th2-woodpecker (1.0.0)

Library that serves as an API for the implementations of th2 load generation tool «Woodpecker».

## Release Notes

### 1.0.0

+ pass load settings to `onStart` method (if any were specified)
+ call `onStart` before each step
+ call `onStop` method after each step or on forced stop
+ ability to load dictionary in generator (via `IMessageGeneratorConfig.readDictionary(alias: String)`)

### 0.1.0

+ Changed `onBatch` function to make it possible to send in several pins
+ Fixed a bug with message filtering by `message_type`
+ Disable waiting for connection recovery when closing the `SubscribeMonitor`
