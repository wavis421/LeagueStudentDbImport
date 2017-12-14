package controller;

import java.util.ArrayList;
import java.util.prefs.Preferences;

import org.joda.time.DateTime;

import model.AttendanceEventModel;
import model.LogDataModel;
import model.MySqlDatabase;
import model.ScheduleModel;
import model.StudentImportModel;
import model.StudentNameModel;

public class StudentTracker {
	// Different port than League Data Manager to allow simultaneous connects
	private static final int LOCAL_SSH_PORT = 6000;

	private MySqlDatabase sqlDb;

	public static void main(String[] args) {
		new StudentTracker();
	}

	public StudentTracker() {
		// Import data starting 4 days ago
		DateTime startDate = new DateTime().minusDays(7);
		String startDateString = startDate.toString().substring(0, 10);

		// Retrieve tokens and passwords
		Preferences prefs = Preferences.userRoot();
		String githubToken = prefs.get("GithubToken", "");
		String pike13Token = prefs.get("Pike13Token", "");
		String awsPassword = prefs.get("AWSPassword", "");

		// Connect to database
		sqlDb = new MySqlDatabase(awsPassword, LOCAL_SSH_PORT);
		if (!sqlDb.connectDatabase()) {
			// TODO: Handle this error
			System.exit(0);
		}

		// Import all the League databases
		Pike13Api pike13Api = new Pike13Api(sqlDb, pike13Token);
		importStudentsFromPike13(pike13Api);
		importAttendanceFromPike13(startDateString, pike13Api);
		importScheduleFromPike13(pike13Api);

		GithubApi githubApi = new GithubApi(sqlDb, githubToken);
		importGithubComments(startDateString, githubApi);

		sqlDb.disconnectDatabase();
		System.exit(0);
	}

	public void importStudentsFromPike13(Pike13Api pike13Api) {
		sqlDb.insertLogData(LogDataModel.STARTING_STUDENT_IMPORT, new StudentNameModel("", "", false), 0, " ***");

		// Get data from Pike13
		ArrayList<StudentImportModel> studentList = pike13Api.getClients();

		// Update changes in database
		if (studentList.size() > 0)
			sqlDb.importStudents(studentList);

		sqlDb.insertLogData(LogDataModel.STUDENT_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0, " ***");
	}

	public void importAttendanceFromPike13(String startDate, Pike13Api pike13Api) {
		sqlDb.insertLogData(LogDataModel.STARTING_ATTENDANCE_IMPORT, new StudentNameModel("", "", false), 0,
				" starting after " + startDate + " ***");

		// Get data from Pike13
		ArrayList<AttendanceEventModel> eventList = pike13Api.getEnrollment(startDate);

		// Update changes in database
		if (eventList.size() > 0)
			sqlDb.importAttendance(eventList);

		sqlDb.insertLogData(LogDataModel.ATTENDANCE_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0, " ***");
	}

	public void importScheduleFromPike13(Pike13Api pike13Api) {
		sqlDb.insertLogData(LogDataModel.STARTING_SCHEDULE_IMPORT, new StudentNameModel("", "", false), 0, " ***");

		// Get data from Pike13
		ArrayList<ScheduleModel> scheduleList = pike13Api.getSchedule();

		// Update changes in database
		if (scheduleList.size() > 0)
			sqlDb.importSchedule(scheduleList);

		sqlDb.insertLogData(LogDataModel.SCHEDULE_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0, " ***");
	}

	public void importGithubComments(String startDate, GithubApi githubApi) {
		boolean result;
		sqlDb.insertLogData(LogDataModel.STARTING_GITHUB_IMPORT, new StudentNameModel("", "", false), 0,
				" starting after " + startDate + " ***");

		result = githubApi.importGithubComments(startDate, 0);
		if (result) {
			githubApi.importGithubCommentsByLevel(0, startDate, 0);
			githubApi.updateMissingGithubComments();

			sqlDb.insertLogData(LogDataModel.GITHUB_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0, " ***");

		} else
			sqlDb.insertLogData(LogDataModel.GITHUB_IMPORT_ABORTED, new StudentNameModel("", "", false), 0,
					": Github API rate limit exceeded ***");
	}
}
