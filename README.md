# breaker
Simple, robust circuit breaker for java; and nothing else.

## Concept

The circuit breaker pattern is used to create [fail-fast](https://en.wikipedia.org/wiki/Fail-fast) systems. Any time a component in the flow of the system (the circuit) is broken, that system typically responds slowly to users since it has to wait for timeouts to confirm failure of the broken component. With circuit breakers, when the component breaks the breaker trips and any subsequent attempts to access the broken component instantly return with an error. After some timeout, the breaker attempts to reset itself by detecting if the component is still broken. If it is then the breaker trips again and continues to fail fast, but if not the breaker resets and the system can be used again as normal.

![state diagram](https://lucidchart.com/publicSegments/view/a7b2d834-aff0-47ef-9741-cc93cf757118/image.png)

## Usage

### Creating a `Breaker`

A `Breaker` with the default configuration can be created with the default constructor.

```java
Breaker breaker = new Breaker();
```

The default configuration is loaded from files used by the [typesafe config library](https://github.com/typesafehub/config). Read more about the config library to learn how to change the defaults. The defaults can also be overridden using the constructor or any of the setter methods.

### Executing operations

To do an operation through the breaker just use the `execute` method.

```java
try{
    breaker.execute(() -> readValueFromDatabase());
}catch(CircuitBreakerClosedException e){
    System.err.println("breaker closed, database must be down");
}catch(Exception e){
    System.err.println("looks like trouble");
}
```

The breaker will automatically detect any exceptions thrown by `readValueFromDatabase` as a failure and re-throw them, or throw `CircuitBreakerClosedException` as a fail fast error.

The single parameter version of execute throws `Exception`, because `Callable` throws `Exception`. Although this means you can throw checked exceptions inside your lambda, it is pretty annoying because it requires such a broad catch block. The breaker library provides two more versions of `execute` to mitigate this.
First, `execute(Callable, boolean)` returns an `Optional` and throws no exceptions. If your `Callable` throws any exceptions (or if the boolean parameter is false and the `Callable` returns null) the breaker will detect a failure and return `Optional.empty`. With this method our code would look like this

```java
Optional result = breaker.execute(() -> readValueFromDatabase(), false);
result.ifPresent(r -> System.out.println(result));
```

Second, the `execute(Callable, Class)` method will only throw `CircuitBreakerClosedException` or an instance of the class parameter. For instance, our earlier code becomes

```java
try{
    breaker.execute(() -> readValueFromDatabase(), MyDatabaseException.class);
}catch(CircuitBreakerClosedException e){
    System.err.println("breaker closed, database must be down");
}catch(MyDatabaseException e){
    System.err.println("looks like trouble");
}
```

much better.

### Event handlers

The breaker library allows you to register event handlers for when the circuit breaker trips or resets. This is especially useful for raising alarms and health monitoring.
The event handlers are `Consumer`s of the `Instant` the event occurs. Set these with `onReset` and `onTrip`.

### Manually manipulating the `Breaker`

Normally the `Breaker` will use exceptions to detect failures, trip and reset all on its own.
However, sometimes you need to indicate a failure some way aside from an exception. For this you can use the `fail` method. For example
```java
breaker.execute(() -> {
    boolean success = tryOperation();
    if(!success)
        breaker.fail();
    return success;
});
```

Other times, you might recieve information during an operation which would make you want to trip the breaker immediately instead of waiting to reach the failure threshold. For this you can use the `trip` method. For example
```java
breaker.execute(() -> {
    Response response = httpRequest();
    if(response.status() == 503)
        breaker.trip();
    else return response;
}, false);
```

### When and where to use a `Breaker`

A circuit breaker should be used any time an operation takes place and it cannot be immediately determined if the operation has succeded for failed.

#### Baked into a library

The easiest place to use a circuit breaker is inside something like a client library for a web service. In this case you should have one static `Breaker` instance shared between each instance of your client. You will likely want to use the breaker to wrap all remote service calls or any other place where an operation has a chance of failure that will not be immediately reported.

#### Stand alone

To get the optimal benefit out of using circuit breakers, you will need to identify groups of parts of your system which will fail together. These will be things which have a bi-directional dependency on each other. These groups will each share a `Breaker` instance. Be careful not to share an instance between components that have a unidirectional or no dependence on each other as this will cause you to lose the usage of components which are in fact fully functional.
