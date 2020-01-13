package org.qora.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.qora.account.PrivateKeyAccount;
import org.qora.account.PublicKeyAccount;
import org.qora.api.ApiError;
import org.qora.api.ApiErrors;
import org.qora.api.ApiException;
import org.qora.api.ApiExceptionFactory;
import org.qora.api.Security;
import org.qora.api.model.ActivitySummary;
import org.qora.api.model.NodeInfo;
import org.qora.block.BlockChain;
import org.qora.controller.Controller;
import org.qora.controller.Synchronizer.SynchronizationResult;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.data.account.MintingAccountData;
import org.qora.data.account.RewardShareData;
import org.qora.network.Network;
import org.qora.network.Peer;
import org.qora.network.PeerAddress;
import org.qora.utils.Base58;
import org.qora.utils.NTP;

import com.google.common.collect.Lists;

@Path("/admin")
@Tag(name = "Admin")
public class AdminResource {

	private static final int MAX_LOG_LINES = 500;

	@Context
	HttpServletRequest request;

	@GET
	@Path("/unused")
	@Parameter(in = ParameterIn.PATH, name = "assetid", description = "Asset ID, 0 is native coin", schema = @Schema(type = "integer"))
	@Parameter(in = ParameterIn.PATH, name = "otherassetid", description = "Asset ID, 0 is native coin", schema = @Schema(type = "integer"))
	@Parameter(in = ParameterIn.PATH, name = "address", description = "an account address", example = "QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v")
	@Parameter(in = ParameterIn.QUERY, name = "count", description = "Maximum number of entries to return, 0 means none", schema = @Schema(type = "integer", defaultValue = "20"))
	@Parameter(in = ParameterIn.QUERY, name = "limit", description = "Maximum number of entries to return, 0 means unlimited", schema = @Schema(type = "integer", defaultValue = "20"))
	@Parameter(in = ParameterIn.QUERY, name = "offset", description = "Starting entry in results, 0 is first entry", schema = @Schema(type = "integer"))
	@Parameter(in = ParameterIn.QUERY, name = "reverse", description = "Reverse results", schema = @Schema(type = "boolean"))
	public String globalParameters() {
		return "";
	}

	@GET
	@Path("/uptime")
	@Operation(
		summary = "Fetch running time of server",
		description = "Returns uptime in milliseconds",
		responses = {
			@ApiResponse(
				description = "uptime in milliseconds",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number"))
			)
		}
	)
	public long uptime() {
		return System.currentTimeMillis() - Controller.startTime;
	}

	@GET
	@Path("/info")
	@Operation(
		summary = "Fetch generic node info",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NodeInfo.class))
			)
		}
	)
	public NodeInfo info() {
		NodeInfo nodeInfo = new NodeInfo();

		nodeInfo.currentTimestamp = NTP.getTime();
		nodeInfo.uptime = System.currentTimeMillis() - Controller.startTime;
		nodeInfo.buildVersion = Controller.getInstance().getVersionString();
		nodeInfo.buildTimestamp = Controller.getInstance().getBuildTimestamp();

		return nodeInfo;
	}

	@GET
	@Path("/stop")
	@Operation(
		summary = "Shutdown",
		description = "Shutdown",
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	public String shutdown() {
		Security.checkApiCallAllowed(request);

		new Thread(() -> {
			// Short sleep to allow HTTP response body to be emitted
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				// Not important
			}

			Controller.getInstance().shutdownAndExit();
		}).start();

		return "true";
	}

	@GET
	@Path("/summary")
	@Operation(
		summary = "Summary of activity since midnight, UTC",
		responses = {
			@ApiResponse(
				content = @Content(schema = @Schema(implementation = ActivitySummary.class))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public ActivitySummary summary() {
		ActivitySummary summary = new ActivitySummary();

		LocalDate date = LocalDate.now();
		LocalTime time = LocalTime.of(0, 0);
		ZoneOffset offset = ZoneOffset.UTC;
		long start = OffsetDateTime.of(date, time, offset).toInstant().toEpochMilli();

		try (final Repository repository = RepositoryManager.getRepository()) {
			int startHeight = repository.getBlockRepository().getHeightFromTimestamp(start);
			int endHeight = repository.getBlockRepository().getBlockchainHeight();

			summary.blockCount = endHeight - startHeight;

			summary.transactionCountByType = repository.getTransactionRepository().getTransactionSummary(startHeight + 1, endHeight);

			for (Integer count : summary.transactionCountByType.values())
				summary.transactionCount += count;

			summary.assetsIssued = repository.getAssetRepository().getRecentAssetIds(start).size();

			summary.namesRegistered = repository.getNameRepository().getRecentNames(start).size();

			return summary;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/mintingaccounts")
	@Operation(
		summary = "List public keys of accounts used to mint blocks by BlockMinter",
		description = "Returns PUBLIC keys of accounts for safety.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = MintingAccountData.class)))
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<MintingAccountData> getMintingAccounts() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<MintingAccountData> mintingAccounts = repository.getAccountRepository().getMintingAccounts();

			// Expand with reward-share data where appropriate
			mintingAccounts = mintingAccounts.stream().map(mintingAccountData -> {
				byte[] publicKey = mintingAccountData.getPublicKey();

				RewardShareData rewardShareData = null;
				try {
					rewardShareData = repository.getAccountRepository().getRewardShare(publicKey);
				} catch (DataException e) {
					// ignore
				}

				return new MintingAccountData(mintingAccountData.getPrivateKey(), rewardShareData);
			}).collect(Collectors.toList());

			return mintingAccounts;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/mintingaccounts")
	@Operation(
		summary = "Add private key of account/reward-share for use by BlockMinter to mint blocks",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "private key"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.REPOSITORY_ISSUE, ApiError.CANNOT_MINT})
	public String addMintingAccount(String seed58) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] seed = Base58.decode(seed58.trim());

			// Check seed is valid
			PrivateKeyAccount mintingAccount = new PrivateKeyAccount(repository, seed);

			// Qortal: account must derive to known reward-share public key
			RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(mintingAccount.getPublicKey());
			if (rewardShareData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			// Qortal: check reward-share's minting account is still allowed to mint
			PublicKeyAccount rewardShareMintingAccount = new PublicKeyAccount(repository, rewardShareData.getMinterPublicKey());
			if (!rewardShareMintingAccount.canMint())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.CANNOT_MINT);

			MintingAccountData mintingAccountData = new MintingAccountData(seed);

			repository.getAccountRepository().save(mintingAccountData);
			repository.saveChanges();
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		return "true";
	}

	@DELETE
	@Path("/mintingaccounts")
	@Operation(
		summary = "Remove account/reward-share from use by BlockMinter, using private key",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "private key"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.REPOSITORY_ISSUE})
	public String deleteMintingAccount(String seed58) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] seed = Base58.decode(seed58.trim());

			if (repository.getAccountRepository().delete(seed) == 0)
				return "false";

			repository.saveChanges();
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}

		return "true";
	}

	@GET
	@Path("/logs")
	@Operation(
		summary = "Return logs entries",
		description = "Limit pegged to 500 max",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	public String fetchLogs(@Parameter(
			ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		LoggerContext loggerContext = (LoggerContext) LogManager.getContext();
		RollingFileAppender fileAppender = (RollingFileAppender) loggerContext.getConfiguration().getAppenders().values().stream().filter(appender -> appender instanceof RollingFileAppender).findFirst().get();

		String filename = fileAppender.getManager().getFileName();
		java.nio.file.Path logPath = Paths.get(filename);

		try {
			List<String> logLines = Files.readAllLines(logPath);

			// Slicing
			if (reverse != null && reverse)
				logLines = Lists.reverse(logLines);

			// offset out of bounds?
			if (offset != null && (offset < 0 || offset >= logLines.size()))
				return "";

			if (offset != null) {
				offset = Math.min(offset, logLines.size() - 1);
				logLines.subList(0, offset).clear();
			}

			// invalid limit
			if (limit != null && limit <= 0)
				return "";

			if (limit != null)
				limit = Math.min(limit, MAX_LOG_LINES);
			else
				limit = MAX_LOG_LINES;

			limit = Math.min(limit, logLines.size());

			logLines.subList(limit - 1, logLines.size()).clear();

			return String.join("\n", logLines);
		} catch (IOException e) {
			return "";
		}
	}

	@POST
	@Path("/orphan")
	@Operation(
		summary = "Discard blocks back to given height.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "0"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_HEIGHT, ApiError.REPOSITORY_ISSUE})
	public String orphan(String targetHeightString) {
		Security.checkApiCallAllowed(request);

		try {
			int targetHeight = Integer.parseUnsignedInt(targetHeightString);

			if (targetHeight <= 0 || targetHeight > Controller.getInstance().getChainHeight())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_HEIGHT);

			if (BlockChain.orphan(targetHeight))
				return "true";
			else
				return "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_HEIGHT);
		} catch (ApiException e) {
			throw e;
		}
	}

	@POST
	@Path("/forcesync")
	@Operation(
		summary = "Forcibly synchronize to given peer.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string", example = "node2.qortal.org"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "\"true\"",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.REPOSITORY_ISSUE})
	public String forceSync(String targetPeerAddress) {
		Security.checkApiCallAllowed(request);

		try {
			// Try to resolve passed address to make things easier
			PeerAddress peerAddress = PeerAddress.fromString(targetPeerAddress);
			InetSocketAddress resolvedAddress = peerAddress.toSocketAddress();

			List<Peer> peers = Network.getInstance().getHandshakedPeers();
			Peer targetPeer = peers.stream().filter(peer -> peer.getResolvedAddress().equals(resolvedAddress)).findFirst().orElse(null);

			if (targetPeer == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			// Try to grab blockchain lock
			ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
			if (!blockchainLock.tryLock(30000, TimeUnit.MILLISECONDS))
				return SynchronizationResult.NO_BLOCKCHAIN_LOCK.name();

			SynchronizationResult syncResult;
			try {
				do {
					syncResult = Controller.getInstance().actuallySynchronize(targetPeer, true);
				} while (syncResult == SynchronizationResult.OK);
			} finally {
				blockchainLock.unlock();
			}

			return syncResult.name();
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (ApiException e) {
			throw e;
		} catch (UnknownHostException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (InterruptedException e) {
			return SynchronizationResult.NO_BLOCKCHAIN_LOCK.name();
		}
	}

}
