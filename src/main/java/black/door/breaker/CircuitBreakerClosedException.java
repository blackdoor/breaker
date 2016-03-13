package black.door.breaker;

/**
 * Created by nfischer on 3/12/2016.
 */
public class CircuitBreakerClosedException extends Exception{
	public CircuitBreakerClosedException(){
		super("Breaker is closed. Retry later or manually reset the breaker " +
				"with Breaker#reset()");
	}
}
