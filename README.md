# th2-woodpecker (0.2.0)

Library that serves as an API for the implementations of th2 load generation tool «Woodpecker».

## Release Notes

### 0.2.0

+ Additional parameter for grpc router inside MessageGeneratorFactory

### 0.1.0

+ Changed `onBatch` function to make it possible to send in several pins
+ Fixed a bug with message filtering by `message_type`
+ Disable waiting for connection recovery when closing the `SubscribeMonitor`
