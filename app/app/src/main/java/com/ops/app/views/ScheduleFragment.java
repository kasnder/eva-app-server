/*
 * Copyright (c) Konrad Kollnig 2015.
 */

package com.ops.app.views;


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.melnykov.fab.FloatingActionButton;
import com.ops.app.R;
import com.ops.app.libs.Common;
import com.ops.app.libs.Model;
import com.ops.app.libs.Settings;
import com.ops.app.stores.Schedule;
import com.ops.app.stores.User;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ScheduleFragment extends Fragment {
	/**
	 * Play Services Variable
	 */
	private final static int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
	public String token;
	/* Save restored ArrayLists */
	ArrayList<Model> restoreSchedule;
	ArrayList<Model> restoreUser;

	/**
	 * Stores the screen orientation during loading
	 */
	//protected int initialOrientation;
	/* UI Components */
	private TextView infoView;
	private View progressView;
	private ListView scheduleList;
	/**
	 * Array Adapter that will hold our ArrayList and display the items on the ListView
	 */
	private ScheduleAdapter scheduleAdapter;
	//private Context context;

	public ScheduleFragment () {
		// Required empty public constructor
	}

	/**
	 * @return Application's version code from the {@code PackageManager}.
	 */
	private static int getAppVersion (Context context) {
		try {
			PackageInfo packageInfo = context.getPackageManager()
					.getPackageInfo(context.getPackageName(), 0);
			return packageInfo.versionCode;
		} catch (Exception e) {
			// should never happen
			throw new RuntimeException("Could not get package name: " + e);
		}
	}

	@Override
	public void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Init stores
		Common.user = new User() {
			@Override
			public void onLoaded (Boolean success) {
				onUserLoaded(success);
			}
		};

		Common.schedule = new Schedule() {
			@Override
			public void onLoaded (Boolean success) {
				onScheduleLoaded(success);
			}
		};

		// Receive Menu Button Clicks
		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateView (LayoutInflater inflater, ViewGroup container,
	                          Bundle savedInstanceState) {
		// Inflate the layout for this fragment
		return inflater.inflate(R.layout.fragment_schedule, container, false);
	}

	@Override
	public void onActivityCreated (Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		Intent callIntent = getActivity().getIntent();

		// Try to restore a saved instance
		if (savedInstanceState != null && !callIntent.getBooleanExtra("force_update", false)) {
			try {
				ArrayList<Model> schedule = (ArrayList<Model>) savedInstanceState.getSerializable("schedule");
				ArrayList<Model> user = (ArrayList<Model>) savedInstanceState.getSerializable("user");

				// Check arrayLists
				// Restore user if set
				if (user != null && user.size() > 0) {
					restoreUser = user;

					// Restore schedule if set
					if (schedule != null && schedule.size() > 0) {
						restoreSchedule = schedule;
					}
				}
			} catch (Exception e) {
				// Do nothing
			}
		}

		// Init UI
		// ListView that will hold our items references back to activity_home.xml_main.xml
		scheduleList = (ListView) getActivity().findViewById(R.id.schedule_list_tabbed);
		View emptyList = getActivity().getLayoutInflater().inflate(R.layout.list_schedule_empty, scheduleList, false);
		scheduleList.setEmptyView(emptyList);

		// Add FAB
		// https://github.com/makovkastar/FloatingActionButton
		FloatingActionButton fab = (FloatingActionButton) getActivity().findViewById(R.id.fab_refresh);
		fab.attachToListView(scheduleList);
		fab.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick (View view) {
				if (isLoading()) return;

				String token = (String) Common.user.get("token");
				loadStores(token);
			}
		});

		// Add an info text to the list
		View header = getActivity().getLayoutInflater().inflate(R.layout.list_schedule_header, scheduleList, false);
		scheduleList.addHeaderView(header);

		progressView = getActivity().findViewById(R.id.tabbed_progress);
		infoView = (TextView) getActivity().findViewById(R.id.infoText);

		// Fill ListView
		// Initialize our array adapter notice how it references the list_schedule_record.xmlecord.xml layout
		scheduleAdapter = new ScheduleAdapter(getActivity(), R.layout.list_schedule_record, Common.schedule.arrayList);

		// Init settings
		Settings.init(getActivity());

		// First start --> try to migrate Sencha Touch Settings
		if (Settings.get(getString(R.string.preference_first_start)) == null) {
			migrateSencha();
			Settings.set(getString(R.string.preference_first_start), "1");
		}

		// Already login in?
		if (callIntent.hasExtra("username") && callIntent.hasExtra("password")) {
			String username = callIntent.getStringExtra("username");
			String password = callIntent.getStringExtra("password");
			loadStores(username, password);
			return;
		}

		// If not try with saved token
		// Try to fetch token
		token = Settings.get(getString(R.string.preference_token));

		// Check intent --> higher priority than settings
		if (callIntent.hasExtra("token")) {
			token = callIntent.getStringExtra("token");
		}

		// No token stored? --> let the user log in
		if (token == null) {
			showLogin();
			return;
		}

		// Finally load everything! :-)
		loadStores(token);
	}

	/**
	 * Tries to fetch username and token set in Sencha Touch App
	 * <p/>
	 * NOTICE: Scince 4.4 Android uses the Chrome Engine for its browser --> different paths && different database layout
	 * <p/>
	 * Path on Pre-KitKat Devices: ./app_database/localstorage/file__0.localstorage
	 * Patch scince KitKat: ./app_webview/Local Storage/file__0.localstorage
	 * <p/>
	 * On Pre-KitKat Devices values were stored as string.
	 * Scince KitKat values are stores as blob.
	 */
	private void migrateSencha () {
		// Get data dir
		String dataDir = getActivity().getApplicationInfo().dataDir; // Should be like /data/data/com.ops.app

		// Check if there's some SQLite Data to parse..
		// Check for Chrome Engine files first (>= 4.4)
		String path = dataDir + "/app_webview/Local Storage/file__0.localstorage";
		File file = new File(path);
		Boolean chromeEngine = true;

		// Check for WebKit Engine files (< 4.4)
		if (!file.exists()) {
			path = dataDir + "/app_database/localstorage/file__0.localstorage";
			file = new File(path);

			// Sorry. There's no chance for you, my friend..
			if (!file.exists()) return;

			chromeEngine = false;
		}

		// Select Settings
		Cursor cursor;
		try {
			SQLiteDatabase db = SQLiteDatabase.openDatabase(path, null, 0);
			String sql = "SELECT value FROM ItemTable";
			cursor = db.rawQuery(sql, null);
		} catch (SQLiteException e) {
			return;
		}

		// Parse settings --> search for username and token
		if (cursor != null && cursor.moveToFirst()) {
			do {
				try {
					// Parse the value
					String value;
					if (chromeEngine) {
						// Parse Sqlite Blob
						value = new String(cursor.getBlob(0), "UTF-8"); // Convert bytes to string
						value = value.replaceAll("\\u0000", ""); // Remove whitespaces
					} else {
						value = cursor.getString(0);
					}

					// Try to parse as Json
					JSONObject jsonObject = new JSONObject(value);

					// Save values if they exist
					if (jsonObject.has("key") && jsonObject.has("value")) {
						String key = jsonObject.getString("key");

						if (key.equals("token")) {
							Settings.set(getString(R.string.preference_token), jsonObject.getString("value"));
						}

						if (key.equals("username")) {
							Settings.set(getString(R.string.preference_username), jsonObject.getString("value"));
						}
					}
				} catch (Exception e) {
					// Do nothing
				}
			} while (cursor.moveToNext());
		}
	}

	@Override
	public void onSaveInstanceState (Bundle savedInstanceState) {
		super.onSaveInstanceState(savedInstanceState);
		// Save UI state changes to the savedInstanceState.
		// This bundle will be passed to onCreate if the process is
		// killed and restarted.
		try {
			if (Common.user.arrayList != null && Common.user.arrayList.size() > 0) {
				savedInstanceState.putSerializable("user", Common.user.arrayList);
			}

			if (Common.schedule.arrayList != null) {
				savedInstanceState.putSerializable("schedule", Common.schedule.arrayList);
			}
		} catch (Exception e) {
			// Do nothing
		}
	}

	@Override
	public boolean onOptionsItemSelected (MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		switch (id) {
			case (R.id.menu_sign_out): {
				stopStores();

				// Delete token and navigate back
				Settings.set(getString(R.string.preference_token), null);

				Intent intent = new Intent(getActivity(), LoginActivity.class);
				startActivity(intent);
				getActivity().finish();
				return true;
			}
		}
		return super.onOptionsItemSelected(item);
	}

	protected void loadStores (String token) {
		loadStores(token, null);
	}

	protected void loadStores (String username, String password) {
		// UI
		showProgress(true);

		// Stop everything
		stopStores();

		// Prevent screen rotation till loaded
		//initialOrientation = getActivity().getRequestedOrientation();
		//getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);

		// Restore instance?
		if (restoreUser != null) {
			Common.user.restore(restoreUser);
			restoreUser = null;
			return;
		}

		// Use token or username + password to auth?
		if (password == null) {
			String token = username;
			Common.user.load(token);
		} else {
			Common.user.load(username, password);
		}
	}

	protected void stopStores () {
		if (isLoading()) {
			Common.user.cancelLoading();
			Common.schedule.cancelLoading();
		}

        /*if (isAdded()) {
            getActivity().setRequestedOrientation(initialOrientation);
        }*/

		// Unset data
		scheduleAdapter.notifyDataSetInvalidated();
		scheduleList.setAdapter(null);
	}

	/**
	 * User logged in? Fine! Load schedule!
	 *
	 * @param success
	 */
	public void onUserLoaded (Boolean success) {
		if (!isAdded()) return;

		// Restore screen orientation TODO Needed?
		//getActivity().setRequestedOrientation(initialOrientation);

		// Anything went wrong?
		if (!success) {
			// Option to retry?
			String token = Settings.get(getString(R.string.preference_token));
			Boolean noRetry = token == null
					|| token.isEmpty()
					|| (Boolean) Common.user.get("noretry", false);

			// Error message provided?
			if (Common.user.errorMessage != null) {
				showLogin(Common.user.errorMessage, noRetry);
			} else {
				showLogin(getString(R.string.error_user_loading_failed), noRetry);
			}
			return;
		}

		// Save everything important to our settings
		token = Common.user.getString("token");

		Settings.set(getString(R.string.preference_token), token);
		Settings.set(getString(R.string.preference_username), Common.user.getString("username"));

		// First time here?
		Boolean gimmeInformation = (Boolean) Common.user.get("gimmeInformation");
		Boolean password2change = (Boolean) Common.user.get("password2change");

		if ((gimmeInformation != null && gimmeInformation)
				|| (password2change != null && password2change)) {

			// Build a setup intent
			Intent intent = new Intent(getActivity(), SetupActivity.class);
			intent.putExtra("token", token);
			intent.putExtra("firstname", (String) Common.user.get("firstname"));
			intent.putExtra("lastname", (String) Common.user.get("lastname"));
			intent.putExtra("isTeacher", Integer.parseInt((String) Common.user.get("isTeacher")));
			intent.putExtra("password2change", (Boolean) Common.user.get("password2change"));
			intent.putExtra("gimmeInformation", (Boolean) Common.user.get("gimmeInformation"));
			startActivity(intent);
			return;
		}

		// Lock rotation
		//getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);

		// Restore instance - check consistency
		if (restoreSchedule != null) {
			Common.schedule.restore(restoreSchedule);
			restoreSchedule = null;
			return;
		}

		// Load schedule
		Common.schedule.load(token);
	}

	/**
	 * Schedule loaded? Just let me finally display everything to the UI..
	 *
	 * @param success
	 */
	public void onScheduleLoaded (Boolean success) {
		if (!isAdded()) return;
		showProgress(false);

		// Restore screen orientation
		//getActivity().setRequestedOrientation(initialOrientation);

		// Anything went wrong?
		if (success) {
			// Set info text
			String lastUpdate = (String) Common.user.get("last_update");
			String info = (String) Common.user.get("info");
			String html = String.format(getString(R.string.info_text), lastUpdate, info);

			infoView.setText(Html.fromHtml(html));
			infoView.setMovementMethod(LinkMovementMethod.getInstance()); // Make links clickable
			infoView.setVisibility(View.VISIBLE);

			// Update list
			scheduleList.setAdapter(scheduleAdapter);
			scheduleAdapter.notifyDataSetChanged();
		} else {
			if (isLoading()) return;
			showLogin(getString(R.string.error_schedule_loading_failed));
		}
	}

	public Boolean isLoading () {
		return Common.user.isLoading() || Common.schedule.isLoading();
	}

	/**
	 * Show login form without message
	 */
	public void showLogin () {
		showLogin(null);
	}

	/**
	 * Show login form with message
	 *
	 * @param message The message to display
	 */
	public void showLogin (String message) {
		showLogin(message, true);
	}

	/**
	 * Show login form with message and option to retry login (with the same token..)
	 *
	 * @param message
	 * @param noRetry
	 */
	public void showLogin (String message, Boolean noRetry) {
		Intent intent = new Intent(getActivity(), LoginActivity.class);

		// Add extras
		intent.putExtra("message", message);

		if (noRetry) {
			Common.user.logOut(getActivity());
		}
		intent.putExtra("noRetry", noRetry);

		// Logged in via username/password? --> restore them in LoginActivity
		Intent callIntent = getActivity().getIntent();
		if (callIntent.hasExtra("username") && callIntent.hasExtra("password")) {
			String username = callIntent.getStringExtra("username");
			String password = callIntent.getStringExtra("password");

			intent.putExtra("username", username);
			intent.putExtra("password", password);
		}

		// Finally switch to login activity
		startActivity(intent);
		getActivity().finish();
	}

	/**
	 * Shows the progress UI and hides it from the activity.
	 */
	@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
	public void showProgress (final boolean show) {
		// On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
		// for very easy animations. If available, use these APIs to fade-in
		// the progress spinner.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
			int animTime = getResources().getInteger(android.R.integer.config_shortAnimTime);

			scheduleList.animate().setDuration(animTime).alpha(
					show ? 0 : 1).setListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd (Animator animation) {
					scheduleList.setVisibility(show ? View.GONE : View.VISIBLE);
				}
			});

			progressView.setVisibility(show ? View.VISIBLE : View.GONE);
			progressView.animate().setDuration(animTime).alpha(
					show ? 1 : 0).setListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd (Animator animation) {
					progressView.setVisibility(show ? View.VISIBLE : View.GONE);
				}
			});
		} else {
			// The ViewPropertyAnimator APIs are not available, so simply show
			// and hide the relevant UI components.
			progressView.setVisibility(show ? View.VISIBLE : View.GONE);
			scheduleList.setVisibility(show ? View.GONE : View.VISIBLE);
		}
	}

	/**
	 * Custom ArrayAdapter to parse Schedule Store into the Schedule ListView
	 */
	class ScheduleAdapter extends ArrayAdapter<Model> {
		final int resource;

		/**
		 * Set up our ScheduleAdapter
		 *
		 * @param context  Activity - HomeTabbedActivity.this
		 * @param resource Layout file - R.layout.list_schedule_record
		 * @param items    ArrayList - Common.schedule.arrayList
		 */
		ScheduleAdapter (Context context, int resource, List<Model> items) {
			super(context, resource, items);
			this.resource = resource;
		}

		@Override
		public View getView (int position, View convertView, ViewGroup parent) {
			Schedule schedule = Common.schedule;

			//LinearLayout recordView;
			ViewHolder viewHolder;

			// Get the current record
			Model record = getItem(position);

			// Inflate the view
			if (convertView == null) {
				convertView = new LinearLayout(getContext());
				String inflater = Context.LAYOUT_INFLATER_SERVICE;
				LayoutInflater layoutInflater;
				layoutInflater = (LayoutInflater) getContext().getSystemService(inflater);
				layoutInflater.inflate(resource, (LinearLayout) convertView, true);

				// Fetch the text boxes from the record layout
				viewHolder = new ViewHolder();
				viewHolder.recordDate = (TextView) convertView.findViewById(R.id.record_date);
				viewHolder.recordStunde = (TextView) convertView.findViewById(R.id.record_stunde);
				viewHolder.recordEntry = (TextView) convertView.findViewById(R.id.record_entry);

				// Format entry -- disabled - caused display problems
				//viewHolder.formattedEntry = formatRecord(record);

				convertView.setTag(viewHolder);
			} else {
				// Restore Ids
				viewHolder = (ViewHolder) convertView.getTag();
			}

			// Assign them their values
			// Check dateX
			Date currentDate = (Date) record.get("datum");
			Boolean showHeader = false;

			// Always show date header for the first header
			if (position == 0) {
				showHeader = true;
			} else {
				// Show also the date header if date changes
				Date beforeDate = (Date) Common.schedule.getAt(position - 1).get("datum");
				if (!currentDate.equals(beforeDate)) {
					showHeader = true;
				}
			}

			if (showHeader) {
				SimpleDateFormat simpleDateFormat = new SimpleDateFormat("EEEE, dd.MM.yyyy");
				viewHolder.recordDate.setText(simpleDateFormat.format(currentDate));
				viewHolder.headerVisibility = View.VISIBLE;
			} else {
				viewHolder.headerVisibility = View.GONE;
			}

			// Show header..
			viewHolder.recordDate.setVisibility(viewHolder.headerVisibility);

			// Add stunde
			viewHolder.recordStunde.setText(record.getString("stunde") + ".");

			// Add entry
			// Fix performance problems?
            /*String formattedEntry = record.getString("formattedEntry");
            if (formattedEntry == null) {
                formattedEntry = formatRecord(record);
                record.put("formattedEntry", formattedEntry);
            }*/
			String formattedEntry = formatRecord(record);
			viewHolder.recordEntry.setText(formattedEntry);

			return convertView;
		}

		/**
		 * Example result: IK (Un/D) Sd Mitbeaufsichtigung (7a) - 321
		 *
		 * @param record Model to be formatted
		 * @return Formatted record as String
		 */
		public String formatRecord (Model record) {
			if (record == null) return null;

			// Define output
			String output = "";

			// Catch parsing errors
			try {
				// Aufsicht?
				if ((Boolean) record.get("aufsicht")) {
					output += "AUFSICHT: ";
				}

				// Who?
				if (record.getString("klassePatch") != null && !record.getString("klassePatch").equals("")) {
					output += record.getString("klassePatch");
				} else if (record.getString("klasse") != null && !record.getString("klasse").equals("")) {
					output += record.getString("klasse");
				}

				// What?
				if (record.getString("lehrerid") != null && !record.getString("lehrerid").equals("")) {
					if (record.getString("fach") != null && !record.getString("fach").equals("")) {
						output += " (" + record.getString("lehrerid") + "/" + record.getString("fach") + ") ";
					} else {
						output += " (" + record.getString("lehrerid") + ") ";
					}
				}

				// And now?
				if (record.getString("vertretung") != null && !record.getString("vertretung").equals("")) {
					output += record.getString("vertretung") + ' ';
				}

				if (record.getString("bemerkung") != null && !record.getString("bemerkung").equals("")) {
					output += record.getString("bemerkung") + ' ';
				}

				// Where?
				if (record.getString("raum") != null && !record.getString("raum").equals("")) {
					output += "- " + record.getString("raum") + ' ';
				}

				// Hightlight!
				if ((Boolean) record.get("wichtig")) {
					output = "!" + output + "!";
				}
			} catch (Exception e) {

			}

			return (output);
		}

		/**
		 * Cache for all Ids
		 */
		class ViewHolder {
			TextView recordDate;
			TextView recordStunde;
			TextView recordEntry;
			int headerVisibility;
			//String formattedEntry;
		}
	}
}
