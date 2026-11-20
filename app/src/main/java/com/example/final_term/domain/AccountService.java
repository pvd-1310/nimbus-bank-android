package com.example.final_term.domain;

import com.example.final_term.domain.DomainDtos.AccountSummary;
import com.google.android.gms.tasks.Task;
import java.util.List;

public interface AccountService { Task<List<AccountSummary>> getAccounts(String ownerUid); }
