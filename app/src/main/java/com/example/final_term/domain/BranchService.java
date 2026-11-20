package com.example.final_term.domain;

import com.example.final_term.domain.DomainDtos.BranchRecommendation;
import com.example.final_term.domain.DomainDtos.Origin;
import com.google.android.gms.tasks.Task;

public interface BranchService { Task<BranchRecommendation> findNearest(Origin origin); }
