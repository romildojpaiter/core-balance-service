package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.Balance
import br.com.itau.challenge.balance.port.output.BalanceTelemetry
import br.com.itau.challenge.balance.port.output.BalanceWriteResult
import br.com.itau.challenge.balance.port.output.BalanceWriter
import br.com.itau.challenge.balance.port.output.TransientProcessingException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException
import software.amazon.awssdk.services.dynamodb.model.ReturnValue
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure

/**
 * The correctness core of the system: one conditional `PutItem`.
 *
 * Everything the specification asks for under ordering, idempotency and concurrency is decided by
 * the single expression below, evaluated **by DynamoDB inside the same atomic operation that
 * writes**. There is no window between deciding and writing, so no interleaving of threads,
 * consumers or instances can produce a lost update — which is why no lock of any kind appears
 * anywhere in this codebase (Constitution V and VI).
 *
 * There is deliberately **no `GetItem` before the `PutItem`** (FR-028). A read-then-write would
 * reintroduce exactly the race the condition eliminates, and would cost a second round trip to
 * arrive at a weaker guarantee.
 */
@Component
class DynamoDbBalanceWriter(
    private val client: DynamoDbClient,
    private val itemMapper: BalanceItemMapper,
    private val telemetry: BalanceTelemetry,
    @param:Value("\${dynamodb.table-name}") private val tableName: String,
) : BalanceWriter {

    override fun save(balance: Balance): BalanceWriteResult {
        val request =
            PutItemRequest
                .builder()
                .tableName(tableName)
                .item(itemMapper.toItem(balance))
                .conditionExpression(CONDITION_EXPRESSION)
                .expressionAttributeNames(EXPRESSION_ATTRIBUTE_NAMES)
                .expressionAttributeValues(
                    mapOf(INCOMING_TIMESTAMP to AttributeValue.fromN(balance.asOf.micros.toString())),
                )
                // Without ALL_OLD the rejection is opaque: we would know the write did not happen but
                // not whether it was a duplicate, a stale event or a tie. A diagnostic GetItem would
                // be the alternative, and it would race with whichever write actually won.
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .returnValues(ReturnValue.NONE)
                .build()

        return try {
            client.putItem(request)
            BalanceWriteResult.Applied
        } catch (e: ConditionalCheckFailedException) {
            classify(balance, e)
        } catch (e: ProvisionedThroughputExceededException) {
            throw transient(balance, "dynamodb throttled the write", e)
        } catch (e: RequestLimitExceededException) {
            throw transient(balance, "dynamodb request limit exceeded", e)
        } catch (e: InternalServerErrorException) {
            throw transient(balance, "dynamodb reported an internal error", e)
        } catch (e: ApiCallAttemptTimeoutException) {
            throw transient(balance, "dynamodb call attempt timed out", e)
        } catch (e: ApiCallTimeoutException) {
            throw transient(balance, "dynamodb call timed out", e)
        } catch (e: SdkClientException) {
            // Connection resets and DNS failures surface here. They are transient by nature: the
            // request may never have reached DynamoDB, and retrying the same conditional put is safe
            // precisely because the condition makes it idempotent.
            throw transient(balance, "dynamodb was unreachable", e)
        }
    }

    /**
     * Names the rejection from the item the condition rejected against.
     *
     * The **order is load-bearing**. Identity is checked before timestamp so that a redelivery of
     * the same event is always `Duplicate`, even though its timestamp necessarily ties with the one
     * already persisted (FR-023). Checking the timestamp first would report every redelivery as a
     * tie and hide idempotency behind a name that suggests a conflict.
     *
     * This is telemetry, not correctness: all three branches decline to write, and the balance is
     * identical whichever label is chosen. If `ALL_OLD` is unavailable the item is empty and the
     * result collapses to `Stale` — granularity is lost, the guarantee is not.
     */
    private fun classify(
        incoming: Balance,
        failure: ConditionalCheckFailedException,
    ): BalanceWriteResult {
        val item = failure.item()
        if (item.isNullOrEmpty()) {
            return BalanceWriteResult.Stale(incoming)
        }

        val persisted = itemMapper.toBalance(item)
        return when {
            persisted.lastTransactionId == incoming.lastTransactionId -> BalanceWriteResult.Duplicate(persisted)
            persisted.asOf == incoming.asOf -> BalanceWriteResult.TimestampTie(persisted)
            else -> BalanceWriteResult.Stale(persisted)
        }
    }

    private fun transient(
        balance: Balance,
        message: String,
        cause: Throwable,
    ): TransientProcessingException {
        telemetry.persistenceFailed(
            BalanceTelemetry.EventContext(
                accountId = balance.accountId.value,
                transactionId = balance.lastTransactionId.value,
                topic = null,
                partition = null,
                offset = null,
            ),
            transient = true,
            cause = cause,
        )
        // The AWS type never leaves this adapter (Constitution III): the input adapter classifies
        // retryability on TransientProcessingException and must not import software.amazon.
        return TransientProcessingException(message, cause)
    }

    private companion object {
        const val PK_ALIAS = "#pk"
        const val TIMESTAMP_ALIAS = "#lastEventTimestamp"
        const val INCOMING_TIMESTAMP = ":incomingTimestamp"

        /**
         * `attribute_not_exists` on the **partition key** is how "no balance yet" is expressed: it is
         * the only attribute guaranteed to exist on every item, so testing any other attribute would
         * confuse "no item" with "item missing a field".
         *
         * The comparison is **strictly** `<`, implementing decision A-03: equal timestamps reject, and
         * the first writer to arrive wins. A `<=` would let two events bearing the same instant
         * overwrite each other in an order decided by the network.
         *
         * Both names are aliased even though only `timestamp` is a DynamoDB reserved word — the alias
         * costs nothing and stops a future rename from producing a runtime-only failure.
         */
        const val CONDITION_EXPRESSION =
            "attribute_not_exists($PK_ALIAS) OR $TIMESTAMP_ALIAS < $INCOMING_TIMESTAMP"

        val EXPRESSION_ATTRIBUTE_NAMES =
            mapOf(
                PK_ALIAS to BalanceTableAttributes.PK,
                TIMESTAMP_ALIAS to BalanceTableAttributes.LAST_EVENT_TIMESTAMP,
            )
    }
}
