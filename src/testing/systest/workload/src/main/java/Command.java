import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.stream.Collectors;
import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.UInt128;

/**
 * A command (creation or query) that can be executed with a client. Every such command has an
 * associated result type.
 */
interface Command<CommandResult extends Result> {
  CommandResult execute(Client client);
}


/**
 * The result of executing some command, which can be reconciled with the model. For creations, this
 * might mean tracking more information about successfully created entities. For queries, it's a
 * hook to check consistency properties.
 */
interface Result {
  void reconcile(Model model);
}


record NewAccount(long id, int ledger, int code, int flags) {
}


record CreateAccounts(ArrayList<NewAccount> accounts) implements Command<CreateAccountsResult> {
  @Override
  public CreateAccountsResult execute(Client client) {
    AccountBatch batch = new AccountBatch(accounts.size());
    for (NewAccount account : accounts) {
      batch.add();
      batch.setId(account.id());
      batch.setLedger(account.ledger());
      batch.setCode(account.code());
      batch.setFlags(account.flags());
    }

    CreateAccountResultBatch accountErrors = client.createAccounts(batch);

    // Collect all failure indices.
    var createAccountFailedIndices = new BitSet(accounts.size());
    while (accountErrors.next()) {
      var index = accountErrors.getIndex();
      createAccountFailedIndices.set(index);
    }

    // We partition the results into created and failed.
    var created = new ArrayList<NewAccount>();
    var failed = new ArrayList<NewAccount>();

    int i = 0;
    for (NewAccount account : accounts) {
      if (createAccountFailedIndices.get(i)) {
        failed.add(account);
      } else {
        created.add(account);
      }
      i++;
    }

    return new CreateAccountsResult(created, failed);
  }
}


record CreateAccountsResult(ArrayList<NewAccount> created, ArrayList<NewAccount> failed)
    implements Result {

  @Override
  public void reconcile(Model model) {
    for (var newAccount : created) {
      assert !model.accounts.containsKey(newAccount.id());
      assert newAccount.ledger() == model.ledger;

      var account = new CreatedAccount(newAccount.id(), newAccount.ledger(), newAccount.code(),
          newAccount.flags());

      model.accounts.put(account.id(), account);
    }
  }

}


record NewTransfer(long id, long debitAccountId, long creditAccountId, int ledger, int code,
    BigInteger amount, int flags) {
}


record CreateTransfers(ArrayList<NewTransfer> transfers) implements Command<CreateTransfersResult> {
  @Override
  public CreateTransfersResult execute(Client client) {
    TransferBatch batch = new TransferBatch(this.transfers.size());
    for (NewTransfer transfer : this.transfers) {
      batch.add();
      batch.setId(transfer.id());
      batch.setDebitAccountId(transfer.debitAccountId());
      batch.setCreditAccountId(transfer.creditAccountId());
      batch.setLedger(transfer.ledger());
      batch.setCode(transfer.code());
      batch.setAmount(transfer.amount());
      batch.setFlags(transfer.flags());
    }

    var transferErrors = client.createTransfers(batch);

    // Collect all failed transfer indices.
    var transferFailedIndices = new BitSet(transfers.size());
    while (transferErrors.next()) {
      var index = transferErrors.getIndex();
      transferFailedIndices.set(index);
    }

    // We partition the results into created and failed.
    var created = new ArrayList<NewTransfer>();
    var failed = new ArrayList<NewTransfer>();

    int i = 0;
    for (NewTransfer transfer : this.transfers) {
      if (transferFailedIndices.get(i)) {
        failed.add(transfer);
      } else {
        created.add(transfer);
      }
      i++;
    }

    return new CreateTransfersResult(created, failed);
  }
}


record CreateTransfersResult(ArrayList<NewTransfer> created, ArrayList<NewTransfer> failed)
    implements Result {

  @Override
  public void reconcile(Model model) {
    for (var transfer : created) {
      var debitAccount = model.accounts.get(transfer.debitAccountId());
      var creditAccount = model.accounts.get(transfer.creditAccountId());
      assert debitAccount != null;
      assert creditAccount != null;
      assert debitAccount.ledger() == creditAccount.ledger();
    }
  }
}


record LookupAccounts(long[] ids) implements Command<LookupAccountsResult> {

  @Override
  public LookupAccountsResult execute(Client client) {
    IdBatch ids = new IdBatch(this.ids.length);
    for (long id : this.ids) {
      ids.add(id);
    }

    AccountBatch accounts = client.lookupAccounts(ids);

    // We assume all ids we lookup are from successfully created accounts.
    assert accounts.getLength() == this.ids.length : "expected all ids of lookup_accounts to exist";

    var results = new ArrayList<QueriedAccount>(accounts.getLength());
    while (accounts.next()) {
      results.add(new QueriedAccount(accounts.getId(UInt128.LeastSignificant), accounts.getLedger(),
          accounts.getCode(), accounts.getFlags(), accounts.getDebitsPosted(),
          accounts.getCreditsPosted()));
    }

    return new LookupAccountsResult(results);
  }
}


record QueriedAccount(long id, int ledger, int code, int flags, BigInteger debitsPosted,
    BigInteger creditsPosted) {
}


record LookupAccountsResult(ArrayList<QueriedAccount> accountsFound) implements Result {
  @Override
  public void reconcile(Model model) {
    // NOTE: These checks assume all known accounts in the ledger were queried.

    // All created accounts are found, and no others.
    assert model.accounts.keySet().equals(accountsFound.stream().map(a -> a.id())
        .collect(Collectors.toSet())) : "all created accounts were not found by query";

    // All accounts found are in the correct ledger.
    for (var account : accountsFound) {
      assert account.ledger() == model.ledger 
        : "found account with another ledger than the model ({} != {})".formatted(
            account.ledger(), 
            model.ledger);
    }

    // Total credits and total debits must be equal over all accounts.
    var diff = this.debitsCreditsDifference(accountsFound);
    assert diff == BigInteger.ZERO : "expected debits and credits to be equal, but got diff: %d"
        .formatted(diff);
  }

  BigInteger debitsCreditsDifference(ArrayList<QueriedAccount> accounts) {
    var debits = BigInteger.ZERO;
    var credits = BigInteger.ZERO;
    for (var account : accounts) {
      debits = debits.add(account.debitsPosted());
      credits = credits.add(account.creditsPosted());
    }
    return debits.subtract(credits);
  }
}

