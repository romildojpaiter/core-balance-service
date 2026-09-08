package br.com.itau.challenge.balance.adapter.output.dynamodb

import br.com.itau.challenge.balance.domain.model.AccountId
import br.com.itau.challenge.balance.domain.model.Balance
import br.com.itau.challenge.balance.port.output.BalanceReader
import br.com.itau.challenge.balance.port.output.TransientProcessingException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException

/**
 * Reads one account's persisted balance.
 *
 * `consistentRead` is on. An eventually consistent read can return the state that preceded a write
 * which has already been acknowledged, and SC-005 requires a query issued after a processed event to
 * observe that event. There is no cache in front of this for the same reason: a cache is an
 * eventually consistent read with a longer and less predictable staleness window.
 *
 * `GetItem` — never `Query`, never `Scan`. The partition key identifies exactly one item, so a scan
 * would read the whole table to find what a single key lookup already returns (FR-030).
 */
@Component
class DynamoDbBalanceReader(
    private val client: DynamoDbClient,
    private val itemMapper: BalanceItemMapper,
    @param:Value("\${dynamodb.table-name}") private val tableName: String,
) : BalanceReader {

    override fun findByAccountId(accountId: AccountId): Balance? {
        val request =
            GetItemRequest
                .builder()
                .tableName(tableName)
                .key(
                    mapOf(
                        BalanceTableAttributes.PK to
                            AttributeValue.fromS(BalanceTableAttributes.partitionKey(accountId)),
                    ),
                ).consistentRead(true)
                .build()

        return try {
            val response = client.getItem(request)
            // hasItem() is the honest check: getItem returns an empty map, not null, for a miss.
            if (!response.hasItem() || response.item().isEmpty()) null else itemMapper.toBalance(response.item())
        } catch (e: ProvisionedThroughputExceededException) {
            throw TransientProcessingException("dynamodb throttled the read", e)
        } catch (e: InternalServerErrorException) {
            throw TransientProcessingException("dynamodb reported an internal error", e)
        } catch (e: ApiCallTimeoutException) {
            throw TransientProcessingException("dynamodb call timed out", e)
        } catch (e: SdkClientException) {
            throw TransientProcessingException("dynamodb was unreachable", e)
        }
    }
}
