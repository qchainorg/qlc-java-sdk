package qlc.mng;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;

import qlc.bean.Account;
import qlc.bean.Address;
import qlc.bean.Pending;
import qlc.bean.Pending.PendingInfo;
import qlc.bean.StateBlock;
import qlc.bean.Token;
import qlc.bean.TokenMeta;
import qlc.network.QlcClient;
import qlc.network.QlcException;
import qlc.utils.Constants;
import qlc.utils.Helper;
import qlc.utils.StringUtil;
import qlc.utils.WorkUtil;

public class TransactionMng {

	/**
	 *  
	 * @Description Return send block by send parameter and private key
	 * @param
	 * 	send parameter for the block
	 *	from: send address for the transaction
	 *	to: receive address for the transaction
	 *	tokenName: token name
	 *	amount: transaction amount
	 *	sender: optional, sms sender
	 *	receiver: optional, sms receiver
	 *	message: optional, sms message hash
	 *	string: private key
	 * @return JSONObject send block
	 * @throws QlcException
	 * @throws IOException 
	 */
	public static JSONObject sendBlock(QlcClient client, String from, String tokenName, String to, 
			BigInteger amount, String sender, String receiver, String message, 
			byte[] privateKey) throws QlcException, IOException {
		
		// token info
		Token token = TokenMng.getTokenByTokenName(client, tokenName);
		
		// send address token info
		TokenMeta tokenMeta = TokenMetaMng.getTokenMeta(client, token.getTokenId(), from);
		if (tokenMeta.getBalance().compareTo(amount) == -1) {
			throw new QlcException(Constants.EXCEPTION_CODE_1000, Constants.EXCEPTION_MSG_1000 + "(balance:" + tokenMeta.getBalance() + ", need:" + amount + ")");
		}
		
		// previous block info
		StateBlock previousBlock = LedgerMng.getBlockInfoByHash(client, Helper.hexStringToBytes(tokenMeta.getHeader()));
		
		// create send block
		StateBlock block = new StateBlock(
				Constants.BLOCK_TYPE_SEND, 
				token.getTokenId(), 
				from, 
				tokenMeta.getBalance().subtract(amount),
				tokenMeta.getHeader(), 
				AccountMng.addressToPublicKey(to), 
				new Long(System.currentTimeMillis()/1000), 
				tokenMeta.getRepresentative());
		if (StringUtil.isNotBlank(sender))
			block.setSender(sender);
		if (StringUtil.isNotBlank(receiver))
			block.setReceiver(receiver);
		if (StringUtil.isNotBlank(message))
			block.setMessage(message);
		else
			block.setMessage(Constants.ZERO_HASH);
			
		block.setVote((previousBlock.getVote()==null) ? new BigInteger("0") : previousBlock.getVote());
		block.setNetwork((previousBlock.getNetwork()==null) ? new BigInteger("0") : previousBlock.getNetwork());
		block.setStorage((previousBlock.getStorage()==null) ? new BigInteger("0") : previousBlock.getStorage());
		block.setOracle((previousBlock.getOracle()==null) ? new BigInteger("0") : previousBlock.getOracle());
		block.setPovHeight((previousBlock.getPovHeight()==null) ? 0l : previousBlock.getPovHeight());
		if (StringUtil.isBlank(previousBlock.getExtra()))
			block.setExtra(Constants.ZERO_HASH);
		else
			block.setExtra(previousBlock.getExtra());

		// block hash
		byte[] hash = BlockMng.getHash(block);
		if (hash==null || hash.length==0)
			throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2000, Constants.EXCEPTION_BLOCK_MSG_2000);

		if (privateKey!=null && privateKey.length==128) {

			// send address info
			Address sendAddress = new Address(from, AccountMng.addressToPublicKey(from), Helper.byteToHexString(privateKey));
			
			// set signature
			String priKey = sendAddress.getPrivateKey().replace(sendAddress.getPublicKey(), "");
			byte[] signature = WalletMng.sign(hash, Helper.hexStringToBytes(priKey));
			boolean signCheck = WalletMng.verify(signature, hash, Helper.hexStringToBytes(sendAddress.getPublicKey()));
			if (!signCheck)
				throw new QlcException(Constants.EXCEPTION_CODE_1005, Constants.EXCEPTION_MSG_1005);
			block.setSignature(Helper.byteToHexString(signature));
			
			// set work
			String work = WorkUtil.generateWork(Helper.hexStringToBytes(BlockMng.getRoot(block)));
			block.setWork(work);
		}
		
		return JSONObject.parseObject(new Gson().toJson(block));
		
	}
	
	/**
	 * 
	 * @Description Return receive block by send block and private key
	 * @param sendBlock
	 * @param privateKey
	 * @throws QlcException
	 * @throws IOException 
	 * @return JSONObject  
	 */
	public static JSONObject receiveBlock(QlcClient client, StateBlock sendBlock, byte[] privateKey) throws QlcException, IOException {
		
		// the block hash
		byte[] sendBlockHash = BlockMng.getHash(sendBlock);
		
		// is send block
		if (!Constants.BLOCK_TYPE_SEND.equals(sendBlock.getType()))
			throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2002, Constants.EXCEPTION_BLOCK_MSG_2002 + ", block hash[" + Helper.byteToHexString(sendBlockHash) + "]");
		
		// Does the send block exist
		StateBlock tempBlock = LedgerMng.getBlockInfoByHash(client, sendBlockHash);
		if (tempBlock == null)
			throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2003, Constants.EXCEPTION_BLOCK_MSG_2003 + ", block hash[" + Helper.byteToHexString(sendBlockHash) + "]");
		
		String receiveAddress = AccountMng.publicKeyToAddress(Helper.hexStringToBytes(sendBlock.getLink()));
		// pending info
		PendingInfo info = null;
		Pending pending = LedgerMng.getAccountPending(client, receiveAddress);
		if (pending != null) {
			List<PendingInfo> itemList = pending.getInfoList();
			if (itemList!=null && itemList.size()>0) {
				PendingInfo tempInfo= null;
				for (int i=0; i<itemList.size(); i++) {
					tempInfo = itemList.get(i);
					if (tempInfo.getHash().equals(Helper.byteToHexString(sendBlockHash))) {
						info = tempInfo;
						break;
					}
					tempInfo = null;
				}
			}
		}
		if (info == null)
			throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2005, Constants.EXCEPTION_BLOCK_MSG_2005);
		
		// has token meta
		TokenMeta tokenMeta = TokenMetaMng.getTokenMeta(client, sendBlock.getToken(), receiveAddress);
		boolean has = false;
		if (tokenMeta != null) 
			has = true;
		
		// create receive block
		StateBlock receiveBlock = new StateBlock();
		if (has) {
			StateBlock previousBlock = LedgerMng.getBlockInfoByHash(client, Helper.hexStringToBytes(tokenMeta.getHeader()));// previous block info
			receiveBlock.setType(Constants.BLOCK_TYPE_RECEIVE);
			receiveBlock.setAddress(receiveAddress);
			receiveBlock.setBalance(tokenMeta.getBalance().add(info.getAmount()));
			receiveBlock.setVote(previousBlock.getVote());
			receiveBlock.setNetwork(previousBlock.getNetwork());
			receiveBlock.setStorage(previousBlock.getStorage());
			receiveBlock.setOracle(previousBlock.getOracle());
			receiveBlock.setPrevious(tokenMeta.getHeader());
			receiveBlock.setLink(Helper.byteToHexString(sendBlockHash));
			receiveBlock.setRepresentative(tokenMeta.getRepresentative());
			receiveBlock.setToken(tokenMeta.getType());
			receiveBlock.setExtra(Constants.ZERO_HASH);
			receiveBlock.setTimestamp(new Long(System.currentTimeMillis()/1000));
		} else {
			receiveBlock.setType(Constants.BLOCK_TYPE_OPEN);
			receiveBlock.setAddress(receiveAddress);
			receiveBlock.setBalance(info.getAmount());
			receiveBlock.setVote(Constants.ZERO_BIG_INTEGER);
			receiveBlock.setNetwork(Constants.ZERO_BIG_INTEGER);
			receiveBlock.setStorage(Constants.ZERO_BIG_INTEGER);
			receiveBlock.setOracle(Constants.ZERO_BIG_INTEGER);
			receiveBlock.setPrevious(Constants.ZERO_HASH);
			receiveBlock.setLink(Helper.byteToHexString(sendBlockHash));
			receiveBlock.setRepresentative(sendBlock.getRepresentative());
			receiveBlock.setToken(sendBlock.getToken());
			receiveBlock.setExtra(Constants.ZERO_HASH);
			receiveBlock.setTimestamp(new Long(System.currentTimeMillis()/1000));
		}
		if (StringUtil.isBlank(sendBlock.getMessage()))
			receiveBlock.setMessage(Constants.ZERO_HASH);
		else
			receiveBlock.setMessage(sendBlock.getMessage());
		receiveBlock.setPovHeight(Constants.ZERO_LONG);

		if (privateKey!=null && privateKey.length==128) {
			
			// check private key and link
			String priKey = Helper.byteToHexString(privateKey);
			String pubKey = priKey.substring(64);
			if (!pubKey.equals(sendBlock.getLink()))
				throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2004, Constants.EXCEPTION_BLOCK_MSG_2004);
			
			// set signature
			byte[] receiveBlockHash = BlockMng.getHash(receiveBlock);
			byte[] signature = WalletMng.sign(receiveBlockHash, Helper.hexStringToBytes(priKey.replace(pubKey, "")));
			boolean signCheck = WalletMng.verify(signature, receiveBlockHash, Helper.hexStringToBytes(pubKey));
			if (!signCheck)
				throw new QlcException(Constants.EXCEPTION_CODE_1005, Constants.EXCEPTION_MSG_1005);
			receiveBlock.setSignature(Helper.byteToHexString(signature));
			
			// set work
			String work = WorkUtil.generateWork(Helper.hexStringToBytes(BlockMng.getRoot(receiveBlock)));
			receiveBlock.setWork(work);
		}
		
		return JSONObject.parseObject(new Gson().toJson(receiveBlock));
		
	}
	
	/**
	 * 
	 * @Description Return change block by account and private key
	 * @param address:account address
	 * @param representative:new representative account
	 * @param chainTokenHash:chian token hash
	 * @param privateKey:private key ,if not set ,will return block without signature and work
	 * @return 
	 * @return JSONObject  
	 * @throws IOException 
	 * @throws QlcException 
	 */
	public static JSONObject changeBlock(QlcClient client, String address, String representative, String chainTokenHash, byte[] privateKey) throws QlcException, IOException {
		
		// check representative
		Account representativeInfo = TokenMetaMng.getAccountMeta(client, representative);
		if (representativeInfo == null)
			throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2006, Constants.EXCEPTION_BLOCK_MSG_2006 + "[" + representative + "]");
		
		// check address
		Account addressInfo = TokenMetaMng.getAccountMeta(client, address);
		if (addressInfo == null)
			throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2007, Constants.EXCEPTION_BLOCK_MSG_2007 + ", address:[" + address + "]");
		
		// account has chain token
		TokenMeta tokenMeta = null;
		List<TokenMeta> tokens = addressInfo.getTokens();
		for (int i=0; i<tokens.size(); i++) {
			tokenMeta = tokens.get(i);
			if (chainTokenHash.equals(tokenMeta.getType()))
				break;
			tokenMeta = null;
		}
		if (tokenMeta == null)
			throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2008, Constants.EXCEPTION_BLOCK_MSG_2008 + ", address:[" + address + "]");

		// previous block info
		StateBlock previousBlock = LedgerMng.getBlockInfoByHash(client, Helper.hexStringToBytes(tokenMeta.getHeader()));
		if (previousBlock == null)
			throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2009, Constants.EXCEPTION_BLOCK_MSG_2009);
			
		// create change block
		StateBlock changeBlock = new StateBlock();
		changeBlock.setType(Constants.BLOCK_TYPE_CHANGE);
		changeBlock.setAddress(address);
		changeBlock.setBalance(tokenMeta.getBalance());
		changeBlock.setVote(previousBlock.getVote());
		changeBlock.setNetwork(previousBlock.getNetwork());
		changeBlock.setStorage(previousBlock.getStorage());
		changeBlock.setOracle(previousBlock.getOracle());
		changeBlock.setPrevious(tokenMeta.getHeader());
		changeBlock.setLink(Constants.ZERO_HASH);
		changeBlock.setRepresentative(representative);
		changeBlock.setToken(tokenMeta.getType());
		changeBlock.setExtra(Constants.ZERO_HASH);
		changeBlock.setTimestamp(new Long(System.currentTimeMillis()/1000));
		changeBlock.setMessage(Constants.ZERO_HASH);
		changeBlock.setPovHeight(Constants.ZERO_LONG);
		
		if (privateKey!=null && privateKey.length==64) {
			
			// check private key and link
			String priKey = Helper.byteToHexString(privateKey);
			String pubKey = priKey.substring(64);
			if (!address.equals(AccountMng.publicKeyToAddress(Helper.hexStringToBytes(pubKey))))
				throw new QlcException(Constants.EXCEPTION_BLOCK_CODE_2004, Constants.EXCEPTION_BLOCK_MSG_2004);
			
			// set signature
			byte[] changeBlockHash = BlockMng.getHash(changeBlock);
			byte[] signature = WalletMng.sign(changeBlockHash, Helper.hexStringToBytes(priKey.replace(pubKey, "")));
			boolean signCheck = WalletMng.verify(signature, changeBlockHash, Helper.hexStringToBytes(pubKey));
			if (!signCheck)
				throw new QlcException(Constants.EXCEPTION_CODE_1005, Constants.EXCEPTION_MSG_1005);
			changeBlock.setSignature(Helper.byteToHexString(signature));
			
			// set work
			String work = WorkUtil.generateWork(Helper.hexStringToBytes(BlockMng.getRoot(changeBlock)));
			changeBlock.setWork(work);
			
		}
		
		return JSONObject.parseObject(new Gson().toJson(changeBlock));
	}

}
