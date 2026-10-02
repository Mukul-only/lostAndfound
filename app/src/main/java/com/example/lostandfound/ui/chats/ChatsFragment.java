package com.example.lostandfound.ui.chats;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Conversation;
import com.example.lostandfound.data.repository.ChatRepository;
import com.example.lostandfound.data.repository.UnreadRepository;
import com.example.lostandfound.databinding.FragmentChatsBinding;
import com.example.lostandfound.ui.chat.ChatActivity;
import java.util.List;

public class ChatsFragment extends Fragment {
    private FragmentChatsBinding binding;
    private ChatRepository chatRepository;
    private ConversationAdapter adapter;
    private UnreadRepository unreadRepository;
    /** Last list handed to the adapter, so a count-only tick does not refetch. */
    private java.util.List<Conversation> loadedConversations;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentChatsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        chatRepository = new ChatRepository(requireContext());
        unreadRepository = UnreadRepository.getInstance(requireContext());

        setupRecyclerView();
        // Counts come from the shared source, not a loop of this screen's own.
        unreadRepository.addListener(this::onUnreadChanged);
        loadConversations();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadConversations();
        unreadRepository.refresh();   // closing a conversation clears its count
    }

    @Override
    public void onPause() {
        super.onPause();
        unreadRepository.removeListener(this::onUnreadChanged);
    }

    /**
     * A count changed somewhere in the app. Rebind the existing rows rather than
     * refetching: a five-second tick that only moved a number must not rebuild
     * the list under the reader's thumb.
     */
    private void onUnreadChanged(java.util.Map<String, Integer> counts, int total) {
        if (binding == null || adapter == null) return;
        adapter.apply(loadedConversations, counts);
    }

    private void setupRecyclerView() {
        adapter = new ConversationAdapter(conversation -> {
            Intent intent = new Intent(requireContext(), ChatActivity.class);
            intent.putExtra("conversation_id", conversation.getId());
            intent.putExtra("report_id", conversation.getReportId());
            intent.putExtra("report_title", conversation.getReportTitle());
            startActivity(intent);
        });

        binding.rvConversations.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvConversations.setAdapter(adapter);
        if (binding.rvConversations.getItemDecorationCount() == 0) {
            binding.rvConversations.addItemDecoration(
                    new com.example.lostandfound.ui.common.FeedDividerDecoration(requireContext()));
        }

        binding.swipeRefreshChats.setOnRefreshListener(this::loadConversations);
        binding.swipeRefreshChats.setColorSchemeColors(
                requireContext().getColor(R.color.spotify_green));
        // White by default; the XML attr is never read by the library, so set it here.
        binding.swipeRefreshChats.setProgressBackgroundColorSchemeColor(
                requireContext().getColor(R.color.spotify_surface2));
    }

    private void loadConversations() {
        binding.progressChats.setVisibility(View.VISIBLE);
        binding.tvChatsEmpty.setVisibility(View.GONE);

        chatRepository.getMyConversations(new ChatRepository.DataCallback<List<Conversation>>() {
            @Override
            public void onSuccess(List<Conversation> conversations) {
                if (!isAdded()) return;
                binding.progressChats.setVisibility(View.GONE);
                binding.swipeRefreshChats.setRefreshing(false);

                loadedConversations = conversations;
                if (conversations == null || conversations.isEmpty()) {
                    binding.tvChatsEmpty.setVisibility(View.VISIBLE);
                    adapter.apply(conversations, unreadRepository.counts());
                } else {
                    binding.tvChatsEmpty.setVisibility(View.GONE);
                    adapter.apply(conversations, unreadRepository.counts());
                }
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                binding.progressChats.setVisibility(View.GONE);
                binding.swipeRefreshChats.setRefreshing(false);
                binding.tvChatsEmpty.setText(message);
                binding.tvChatsEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (unreadRepository != null) {
            unreadRepository.removeListener(this::onUnreadChanged);
        }
        binding = null;
    }
}
