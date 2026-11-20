package com.example.final_term.domain;

import com.example.final_term.domain.DomainDtos.ChatMessage;
import com.example.final_term.domain.DomainDtos.ChatReply;
import com.google.android.gms.tasks.Task;

public interface ChatbotService { Task<ChatReply> reply(ChatMessage message); }
