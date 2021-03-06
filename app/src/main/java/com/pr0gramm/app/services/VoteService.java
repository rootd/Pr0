package com.pr0gramm.app.services;

import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;

import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import com.google.common.io.LittleEndianDataInputStream;
import com.pr0gramm.app.api.pr0gramm.Api;
import com.pr0gramm.app.feed.FeedItem;
import com.pr0gramm.app.feed.Nothing;
import com.pr0gramm.app.feed.Vote;
import com.pr0gramm.app.orm.CachedVote;
import com.pr0gramm.app.util.BackgroundScheduler;
import com.pr0gramm.app.util.Holder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import gnu.trove.TCollections;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import rx.Observable;
import rx.util.async.Async;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Stopwatch.createStarted;
import static com.google.common.collect.Lists.transform;
import static com.pr0gramm.app.orm.CachedVote.Type.ITEM;
import static com.pr0gramm.app.util.AndroidUtility.checkNotMainThread;
import static com.pr0gramm.app.util.Databases.withTransaction;

/**
 */
@Singleton
public class VoteService {
    public static final TLongObjectMap<Vote> NO_VOTES = TCollections.unmodifiableMap(new TLongObjectHashMap<>());

    private static final Logger logger = LoggerFactory.getLogger("VoteService");

    private final Api api;
    private final SeenService seenService;

    private final Holder<SQLiteDatabase> database;

    @Inject
    public VoteService(Api api, SeenService seenService, Holder<SQLiteDatabase> database) {
        this.api = api;
        this.seenService = seenService;
        this.database = database;
    }

    /**
     * Votes a post. This sends a request to the server, so you need to be signed in
     * to vote posts.
     *
     * @param item The item that is to be voted
     * @param vote The vote to send to the server
     */
    public Observable<Nothing> vote(FeedItem item, Vote vote) {
        logger.info("Voting feed item {} {}", item.id(), vote);
        Track.votePost(vote);

        AsyncTask.execute(() -> storeVoteValueInTx(CachedVote.Type.ITEM, item.id(), vote));
        return api.vote(null, item.id(), vote.getVoteValue());
    }

    public Observable<Nothing> vote(Api.Comment comment, Vote vote) {
        logger.info("Voting comment {} {}", comment.getId(), vote);
        Track.voteComment(vote);

        AsyncTask.execute(() -> storeVoteValueInTx(CachedVote.Type.COMMENT, comment.getId(), vote));
        return api.voteComment(null, comment.getId(), vote.getVoteValue());
    }

    public Observable<Nothing> vote(Api.Tag tag, Vote vote) {
        logger.info("Voting tag {} {}", tag.getId(), vote);
        Track.voteTag(vote);

        AsyncTask.execute(() -> storeVoteValueInTx(CachedVote.Type.TAG, tag.getId(), vote));
        return api.voteTag(null, tag.getId(), vote.getVoteValue());
    }

    /**
     * Gets the vote for an item.
     *
     * @param item The item to get the vote for.
     */
    public Observable<Vote> getVote(FeedItem item) {
        return Async
                .fromCallable(
                        () -> CachedVote.find(database.value(), ITEM, item.id()),
                        BackgroundScheduler.instance())
                .map(vote -> vote.transform(v -> v.vote).or(Vote.NEUTRAL));
    }

    /**
     * Stores the vote value. This creates a transaction to prevent lost updates.
     *
     * @param type   The type of vote to store in the vote cache.
     * @param itemId The id of the item to vote
     * @param vote   The vote to store for that item
     */
    private void storeVoteValueInTx(CachedVote.Type type, long itemId, Vote vote) {
        checkNotMainThread();
        withTransaction(database.value(), () -> storeVoteValue(type, itemId, vote));
    }

    /**
     * Stores a vote value for an item with the given id.
     * This method must be called inside of an transaction to guarantee
     * consistency.
     *
     * @param type   The type of vote to store in the vote cache.
     * @param itemId The id of the item to vote
     * @param vote   The vote to store for that item
     */
    private void storeVoteValue(CachedVote.Type type, long itemId, Vote vote) {
        checkNotMainThread();
        CachedVote.quickSave(database.value(), type, itemId, vote);
    }

    /**
     * Applies the given voting actions from the log.
     *
     * @param actions The actions from the log to apply.
     */
    void applyVoteActions(String actions) {
        if (actions.isEmpty())
            return;

        byte[] decoded = BaseEncoding.base64().decode(actions);
        checkArgument(decoded.length % 5 == 0, "Length of vote log must be a multiple of 5");

        int actionCount = decoded.length / 5;
        LittleEndianDataInputStream actionStream = new LittleEndianDataInputStream(
                new ByteArrayInputStream(decoded));

        AtomicReference<Exception> errorRef = new AtomicReference<>();

        Stopwatch watch = Stopwatch.createStarted();
        withTransaction(database.value(), () -> {
            logger.info("Applying {} vote actions", actionCount);
            try {
                for (int idx = 0; idx < actionCount; idx++) {
                    long id = actionStream.readInt();
                    VoteAction action = VOTE_ACTIONS.get(actionStream.readUnsignedByte());
                    if (action == null)
                        continue;

                    storeVoteValue(action.type, id, action.vote);
                    if (action.type == ITEM) {
                        seenService.markAsSeen((int) id);
                    }
                }
            } catch (Exception error) {
                // doInTransaction consumes exceptions -.-
                errorRef.set(error);
                throw Throwables.propagate(error);
            }
        });

        logger.info("Applying vote actions took {}", watch);

        // re-throw error
        Exception error = errorRef.get();
        if (error != null) {
            throw Throwables.propagate(error);
        }
    }

    /**
     * Tags the given post. This methods adds the tags to the given post
     * and returns a list of tags.
     */
    public Observable<List<Api.Tag>> tag(FeedItem feedItem, List<String> tags) {
        String tagString = Joiner.on(",").join(transform(tags, tag -> tag.replace(',', ' ')));
        return api.addTags(null, feedItem.id(), tagString).map(response -> {
            withTransaction(database.value(), () -> {
                // auto-apply up-vote to newly created tags
                for (long tagId : response.getTagIds())
                    storeVoteValue(CachedVote.Type.TAG, tagId, Vote.UP);
            });

            return response.getTags();
        });
    }

    /**
     * Writes a comment to the given post.
     */
    public Observable<Api.NewComment> postComment(long itemId, long parentId, String comment) {
        return api.postComment(null, itemId, parentId, comment)
                .filter(response -> response.getComments().size() >= 1)
                .map(response -> {
                    // store the implicit upvote for the comment.
                    storeVoteValueInTx(CachedVote.Type.COMMENT, response.getCommentId(), Vote.UP);
                    return response;
                });
    }

    public Observable<Api.NewComment> postComment(FeedItem item, long parentId, String comment) {
        return postComment(item.id(), parentId, comment);
    }

    /**
     * Removes all votes from the vote cache.
     */
    public void clear() {
        logger.info("Removing all items from vote cache");
        CachedVote.clear(database.value());
    }

    /**
     * Gets the votes for the given comments
     *
     * @param comments A list of comments to get the votes for.
     * @return A map containing the vote from commentId to vote
     */
    public Observable<TLongObjectMap<Vote>> getCommentVotes(List<Api.Comment> comments) {
        List<Long> ids = transform(comments, Api.Comment::getId);
        return findCachedVotes(CachedVote.Type.COMMENT, ids);
    }

    public Observable<TLongObjectMap<Vote>> getTagVotes(List<Api.Tag> tags) {
        List<Long> ids = transform(tags, Api.Tag::getId);
        return findCachedVotes(CachedVote.Type.TAG, ids);
    }

    private Observable<TLongObjectMap<Vote>> findCachedVotes(CachedVote.Type type, List<Long> ids) {
        if (ids.isEmpty())
            return Observable.just(NO_VOTES);

        return Async.start(() -> {
            Stopwatch watch = createStarted();
            List<CachedVote> cachedVotes = CachedVote.find(database.value(), type, ids);

            TLongObjectHashMap<Vote> result = new TLongObjectHashMap<>();
            for (CachedVote cachedVote : cachedVotes)
                result.put(cachedVote.itemId, cachedVote.vote);

            logger.info("Loading votes for {} {}s took {}", ids.size(), type.name().toLowerCase(), watch);
            return result;
        }, BackgroundScheduler.instance());
    }

    private static class VoteAction {
        final CachedVote.Type type;
        final Vote vote;

        VoteAction(CachedVote.Type type, Vote vote) {
            this.type = type;
            this.vote = vote;
        }
    }

    private static final Map<Integer, VoteAction> VOTE_ACTIONS = new ImmutableMap.Builder<Integer, VoteAction>()
            .put(1, new VoteAction(CachedVote.Type.ITEM, Vote.DOWN))
            .put(2, new VoteAction(CachedVote.Type.ITEM, Vote.NEUTRAL))
            .put(3, new VoteAction(CachedVote.Type.ITEM, Vote.UP))
            .put(4, new VoteAction(CachedVote.Type.COMMENT, Vote.DOWN))
            .put(5, new VoteAction(CachedVote.Type.COMMENT, Vote.NEUTRAL))
            .put(6, new VoteAction(CachedVote.Type.COMMENT, Vote.UP))
            .put(7, new VoteAction(CachedVote.Type.TAG, Vote.DOWN))
            .put(8, new VoteAction(CachedVote.Type.TAG, Vote.NEUTRAL))
            .put(9, new VoteAction(CachedVote.Type.TAG, Vote.UP))
            .put(10, new VoteAction(CachedVote.Type.ITEM, Vote.FAVORITE))
            .build();
}
