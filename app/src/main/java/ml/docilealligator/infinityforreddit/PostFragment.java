package ml.docilealligator.infinityforreddit;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.lsjwzh.widget.materialloadingprogressbar.CircleProgressBar;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Named;

import butterknife.BindView;
import butterknife.ButterKnife;
import retrofit2.Retrofit;


/**
 * A simple {@link Fragment} subclass.
 */
public class PostFragment extends Fragment implements FragmentCommunicator {

    static final String EXTRA_NAME = "EN";
    static final String EXTRA_USER_NAME = "EN";
    static final String EXTRA_USER_WHERE = "EUW";
    static final String EXTRA_QUERY = "EQ";
    static final String EXTRA_POST_TYPE = "EPT";
    static final String EXTRA_SORT_TYPE = "EST";
    static final String EXTRA_FILTER = "EF";
    static final int EXTRA_NO_FILTER = -1;
    static final String EXTRA_ACCESS_TOKEN = "EAT";

    private static final String IS_IN_LAZY_MODE_STATE = "IILMS";

    @BindView(R.id.coordinator_layout_post_fragment) CoordinatorLayout mCoordinatorLayout;
    @BindView(R.id.recycler_view_post_fragment) RecyclerView mPostRecyclerView;
    @BindView(R.id.progress_bar_post_fragment) CircleProgressBar mProgressBar;
    @BindView(R.id.fetch_post_info_linear_layout_post_fragment) LinearLayout mFetchPostInfoLinearLayout;
    @BindView(R.id.fetch_post_info_image_view_post_fragment) ImageView mFetchPostInfoImageView;
    @BindView(R.id.fetch_post_info_text_view_post_fragment) TextView mFetchPostInfoTextView;

    private RequestManager mGlide;

    private Activity activity;
    private LinearLayoutManager mLinearLayoutManager;

    private boolean isInLazyMode = false;
    private boolean isLazyModePaused = false;

    private PostRecyclerViewAdapter mAdapter;
    private RecyclerView.SmoothScroller smoothScroller;

    PostViewModel mPostViewModel;

    private Window window;
    private Handler lazyModeHandler;
    private Runnable lazyModeRunnable;
    private CountDownTimer resumeLazyModeCountDownTimer;

    @Inject @Named("no_oauth")
    Retrofit mRetrofit;

    @Inject @Named("oauth")
    Retrofit mOauthRetrofit;

    @Inject
    RedditDataRoomDatabase redditDataRoomDatabase;

    public PostFragment() {
        // Required empty public constructor
    }

    @Override
    public void onResume() {
        super.onResume();
        if(mPostRecyclerView.getAdapter() != null) {
            ((PostRecyclerViewAdapter) mPostRecyclerView.getAdapter()).setCanStartActivity(true);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_post, container, false);

        ((Infinity) activity.getApplication()).getAppComponent().inject(this);

        ButterKnife.bind(this, rootView);

        EventBus.getDefault().register(this);

        lazyModeHandler = new Handler();

        smoothScroller = new LinearSmoothScroller(activity) {
            @Override
            protected int getVerticalSnapPreference() {
                return LinearSmoothScroller.SNAP_TO_START;
            }
        };

        window = activity.getWindow();

        Resources resources = getResources();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            if (resources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT || resources.getBoolean(R.bool.isTablet)) {
                int navBarResourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android");
                if (navBarResourceId > 0) {
                    mPostRecyclerView.setPadding(0, 0, 0, resources.getDimensionPixelSize(navBarResourceId));
                }
            }
        }

        mGlide = Glide.with(activity);

        lazyModeRunnable = new Runnable() {
            @Override
            public void run() {
                if(isInLazyMode && !isLazyModePaused) {
                    int nPosts = mAdapter.getItemCount();
                    int firstVisiblePosition = mLinearLayoutManager.findFirstVisibleItemPosition();
                    if(firstVisiblePosition != RecyclerView.NO_POSITION && nPosts > firstVisiblePosition) {
                        smoothScroller.setTargetPosition(firstVisiblePosition + 1);
                        mLinearLayoutManager.startSmoothScroll(smoothScroller);
                    }
                }
                lazyModeHandler.postDelayed(this, 2500);
            }
        };

        resumeLazyModeCountDownTimer = new CountDownTimer(2500, 2500) {
            @Override
            public void onTick(long l) {

            }

            @Override
            public void onFinish() {
                resumeLazyMode(true);
            }
        };

        if(savedInstanceState != null) {
            isInLazyMode = savedInstanceState.getBoolean(IS_IN_LAZY_MODE_STATE);
            if(isInLazyMode) {
                resumeLazyMode(false);
            }
        }

        mLinearLayoutManager = new LinearLayoutManager(activity);
        mPostRecyclerView.setLayoutManager(mLinearLayoutManager);
        mPostRecyclerView.setOnTouchListener((view, motionEvent) -> {
            if(isInLazyMode) {
                pauseLazyMode(true);
            }
            return false;
        });

        int postType = getArguments().getInt(EXTRA_POST_TYPE);
        String sortType = getArguments().getString(EXTRA_SORT_TYPE);
        int filter = getArguments().getInt(EXTRA_FILTER);
        String accessToken = getArguments().getString(EXTRA_ACCESS_TOKEN);

        PostViewModel.Factory factory;

        if(postType == PostDataSource.TYPE_SEARCH) {
            String subredditName = getArguments().getString(EXTRA_NAME);
            String query = getArguments().getString(EXTRA_QUERY);

            mAdapter = new PostRecyclerViewAdapter(activity, mOauthRetrofit, mRetrofit, redditDataRoomDatabase,
                    accessToken, postType, true, new PostRecyclerViewAdapter.Callback() {
                @Override
                public void retryLoadingMore() {
                    mPostViewModel.retryLoadingMore();
                }

                @Override
                public void typeChipClicked(int filter) {
                    Intent intent = new Intent(activity, FilteredThingActivity.class);
                    intent.putExtra(FilteredThingActivity.EXTRA_NAME, subredditName);
                    intent.putExtra(FilteredThingActivity.EXTRA_QUERY, query);
                    intent.putExtra(FilteredThingActivity.EXTRA_POST_TYPE, postType);
                    intent.putExtra(FilteredThingActivity.EXTRA_SORT_TYPE, sortType);
                    intent.putExtra(FilteredThingActivity.EXTRA_FILTER, filter);
                    startActivity(intent);
                }
            });

            if(accessToken == null) {
                factory = new PostViewModel.Factory(mRetrofit, accessToken,
                        getResources().getConfiguration().locale, subredditName, query, postType, sortType, filter);
            } else {
                factory = new PostViewModel.Factory(mOauthRetrofit, accessToken,
                        getResources().getConfiguration().locale, subredditName, query, postType, sortType, filter);
            }
        } else if(postType == PostDataSource.TYPE_SUBREDDIT) {
            String subredditName = getArguments().getString(EXTRA_NAME);

            boolean displaySubredditName = subredditName.equals("popular") || subredditName.equals("all");
            mAdapter = new PostRecyclerViewAdapter(activity, mOauthRetrofit, mRetrofit, redditDataRoomDatabase,
                    accessToken, postType, displaySubredditName, new PostRecyclerViewAdapter.Callback() {
                @Override
                public void retryLoadingMore() {
                    mPostViewModel.retryLoadingMore();
                }

                @Override
                public void typeChipClicked(int filter) {
                    Intent intent = new Intent(activity, FilteredThingActivity.class);
                    intent.putExtra(FilteredThingActivity.EXTRA_NAME, subredditName);
                    intent.putExtra(FilteredThingActivity.EXTRA_POST_TYPE, postType);
                    intent.putExtra(FilteredThingActivity.EXTRA_SORT_TYPE, sortType);
                    intent.putExtra(FilteredThingActivity.EXTRA_FILTER, filter);
                    startActivity(intent);
                }
            });

            if(accessToken == null) {
                factory = new PostViewModel.Factory(mRetrofit, accessToken,
                        getResources().getConfiguration().locale, subredditName, postType, sortType, filter);
            } else {
                factory = new PostViewModel.Factory(mOauthRetrofit, accessToken,
                        getResources().getConfiguration().locale, subredditName, postType, sortType, filter);
            }
        } else if(postType == PostDataSource.TYPE_USER) {
            CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) mFetchPostInfoLinearLayout.getLayoutParams();
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            mFetchPostInfoLinearLayout.setLayoutParams(params);

            String username = getArguments().getString(EXTRA_USER_NAME);
            String where = getArguments().getString(EXTRA_USER_WHERE);

            mAdapter = new PostRecyclerViewAdapter(activity, mOauthRetrofit, mRetrofit, redditDataRoomDatabase,
                    accessToken, postType, true, new PostRecyclerViewAdapter.Callback() {
                @Override
                public void retryLoadingMore() {
                    mPostViewModel.retryLoadingMore();
                }

                @Override
                public void typeChipClicked(int filter) {
                    Intent intent = new Intent(activity, FilteredThingActivity.class);
                    intent.putExtra(FilteredThingActivity.EXTRA_NAME, username);
                    intent.putExtra(FilteredThingActivity.EXTRA_POST_TYPE, postType);
                    intent.putExtra(FilteredThingActivity.EXTRA_SORT_TYPE, sortType);
                    intent.putExtra(FilteredThingActivity.EXTRA_USER_WHERE, where);
                    intent.putExtra(FilteredThingActivity.EXTRA_FILTER, filter);
                    startActivity(intent);
                }
            });

            if(accessToken == null) {
                factory = new PostViewModel.Factory(mRetrofit, accessToken,
                        getResources().getConfiguration().locale, username, postType, sortType, where, filter);
            } else {
                factory = new PostViewModel.Factory(mOauthRetrofit, accessToken,
                        getResources().getConfiguration().locale, username, postType, sortType, where, filter);
            }
        } else {
            mAdapter = new PostRecyclerViewAdapter(activity, mOauthRetrofit, mRetrofit, redditDataRoomDatabase,
                    accessToken, postType, true, new PostRecyclerViewAdapter.Callback() {
                @Override
                public void retryLoadingMore() {
                    mPostViewModel.retryLoadingMore();
                }

                @Override
                public void typeChipClicked(int filter) {
                    Intent intent = new Intent(activity, FilteredThingActivity.class);
                    intent.putExtra(FilteredThingActivity.EXTRA_NAME, activity.getString(R.string.best));
                    intent.putExtra(FilteredThingActivity.EXTRA_POST_TYPE, postType);
                    intent.putExtra(FilteredThingActivity.EXTRA_SORT_TYPE, sortType);
                    intent.putExtra(FilteredThingActivity.EXTRA_FILTER, filter);
                    startActivity(intent);
                }
            });

            factory = new PostViewModel.Factory(mOauthRetrofit, accessToken,
                    getResources().getConfiguration().locale, postType, sortType, filter);
        }

        mPostRecyclerView.setAdapter(mAdapter);

        mPostViewModel = new ViewModelProvider(this, factory).get(PostViewModel.class);
        mPostViewModel.getPosts().observe(this, posts -> mAdapter.submitList(posts));

        mPostViewModel.getInitialLoadingState().observe(this, networkState -> {
            if(networkState.getStatus().equals(NetworkState.Status.SUCCESS)) {
                mProgressBar.setVisibility(View.GONE);
            } else if(networkState.getStatus().equals(NetworkState.Status.FAILED)) {
                mFetchPostInfoLinearLayout.setOnClickListener(view -> mPostViewModel.retry());
                showErrorView(R.string.load_posts_error);
            } else {
                mFetchPostInfoLinearLayout.setVisibility(View.GONE);
                mProgressBar.setVisibility(View.VISIBLE);
            }
        });

        mPostViewModel.hasPost().observe(this, hasPost -> {
            if(hasPost) {
                mFetchPostInfoLinearLayout.setVisibility(View.GONE);
            } else {
                showErrorView(R.string.no_posts);
            }
        });

        mPostViewModel.getPaginationNetworkState().observe(this, networkState -> {
            mAdapter.setNetworkState(networkState);
        });

        return rootView;
    }

    void changeSortType(String sortType) {
        mPostViewModel.changeSortType(sortType);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.activity = (Activity) context;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(IS_IN_LAZY_MODE_STATE, isInLazyMode);
    }

    @Override
    public void refresh() {
        mPostViewModel.refresh();
    }

    private void showErrorView(int stringResId) {
        mProgressBar.setVisibility(View.GONE);
        if(activity != null && isAdded()) {
            mFetchPostInfoLinearLayout.setVisibility(View.VISIBLE);
            mFetchPostInfoTextView.setText(stringResId);
            mGlide.load(R.drawable.load_post_error_indicator).into(mFetchPostInfoImageView);
        }
    }

    @Override
    public void startLazyMode() {
        isInLazyMode = true;
        isLazyModePaused = false;
        lazyModeHandler.postDelayed(lazyModeRunnable, 2500);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Toast.makeText(activity, getString(R.string.lazy_mode_start, 2.5), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void stopLazyMode() {
        isInLazyMode = false;
        isLazyModePaused = false;
        lazyModeHandler.removeCallbacks(lazyModeRunnable);
        resumeLazyModeCountDownTimer.cancel();
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Toast.makeText(activity, getString(R.string.lazy_mode_stop), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void resumeLazyMode(boolean resumeNow) {
        if(isInLazyMode) {
            isLazyModePaused = false;
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if(resumeNow) {
                lazyModeHandler.post(lazyModeRunnable);
            } else {
                lazyModeHandler.postDelayed(lazyModeRunnable, 2500);
            }
        }
    }

    @Override
    public void pauseLazyMode(boolean startTimer) {
        resumeLazyModeCountDownTimer.cancel();
        isInLazyMode = true;
        isLazyModePaused = true;
        lazyModeHandler.removeCallbacks(lazyModeRunnable);
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if(startTimer) {
            resumeLazyModeCountDownTimer.start();
        }
    }

    @Override
    public boolean isInLazyMode() {
        return isInLazyMode;
    }

    @Subscribe
    public void onPostUpdateEvent(PostUpdateEventToPostList event) {
        Post post = mAdapter.getCurrentList().get(event.positionInList);
        if(post != null) {
            post.setTitle(event.post.getTitle());
            post.setVoteType(event.post.getVoteType());
            post.setScore(event.post.getScore());
            post.setNSFW(event.post.isNSFW());
            post.setSpoiler(event.post.isSpoiler());
            post.setFlair(event.post.getFlair());
            mAdapter.notifyItemChanged(event.positionInList);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if(isInLazyMode) {
            resumeLazyMode(false);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if(isInLazyMode) {
            pauseLazyMode(false);
        }
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}