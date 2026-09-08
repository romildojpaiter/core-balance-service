package br.com.itau.challenge.balance.port.output

/**
 * A failure that may succeed if the same operation is attempted again.
 *
 * This lives in `port.output` rather than in an adapter because of who throws it and who catches it:
 * the DynamoDB writer raises it, and the Kafka error handler classifies on it. Putting it in the
 * Kafka package would force the output adapter to import the input adapter — an adapter-to-adapter
 * edge that Constitution III forbids and that would couple persistence to whichever transport
 * happens to drive it today. As a port type it is simply part of the contract [BalanceWriter]
 * offers to any caller.
 *
 * The counterpart — a payload that cannot be interpreted at all — is genuinely transport-specific
 * and stays in the Kafka adapter as `UnprocessableEventException`.
 */
class TransientProcessingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
