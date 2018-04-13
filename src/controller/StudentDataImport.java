package controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.prefs.Preferences;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import model.AttendanceEventModel;
import model.LogDataModel;
import model.MySqlDatabase;
import model.ScheduleModel;
import model.StudentImportModel;
import model.StudentModel;
import model.StudentNameModel;

public class StudentDataImport {
	private MySqlDatabase sqlDb;

	public static void main(String[] args) {
		new StudentDataImport().importStudentTrackerData();
	}

	public void importStudentTrackerData() {
		// Import data starting 7 days ago
		String startDateString = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles")).minusDays(7)
				.toString().substring(0, 10);

		// Retrieve tokens and passwords
		Preferences prefs = Preferences.userRoot();
		String githubToken = prefs.get("GithubToken", "");
		if (githubToken.equals(""))
			githubToken = readFile("./githubToken.txt");
		String pike13Token = prefs.get("Pike13Token", "");
		if (pike13Token.equals(""))
			pike13Token = readFile("./pike13Token.txt");
		String awsPassword = prefs.get("AWSPassword", "");
		if (awsPassword.equals(""))
			awsPassword = readFile("./awsPassword.txt");

		// Connect to database
		sqlDb = new MySqlDatabase(awsPassword, MySqlDatabase.STUDENT_IMPORT_SSH_PORT);
		if (!sqlDb.connectDatabase()) {
			// TODO: Handle this error
			System.out.println("Failed to connect to MySql database");
			System.exit(0);
		}

		// Connect to Pike13 and import data
		Pike13Api pike13Api = new Pike13Api(sqlDb, pike13Token);
		importStudentsFromPike13(pike13Api);
		importAttendanceFromPike13(startDateString, pike13Api);
		importScheduleFromPike13(pike13Api);

		// Connect to Github and import data
		GithubApi githubApi = new GithubApi(sqlDb, githubToken);
		importGithubComments(startDateString, githubApi);

		sqlDb.disconnectDatabase();
		System.exit(0);
	}

	public void importStudentsFromPike13(Pike13Api pike13Api) {
		String today = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles")).toString("yyyy-MM-dd")
				.substring(0, 10);
		sqlDb.insertLogData(LogDataModel.STARTING_STUDENT_IMPORT, new StudentNameModel("", "", false), 0,
				" for " + today + " ***");

		// Get data from Pike13
		ArrayList<StudentImportModel> studentList = pike13Api.getClients();

		// Update changes in database
		if (studentList.size() > 0)
			sqlDb.importStudents(studentList);

		sqlDb.insertLogData(LogDataModel.STUDENT_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" for " + today + " ***");
	}

	public void importAttendanceFromPike13(String startDate, Pike13Api pike13Api) {
		sqlDb.insertLogData(LogDataModel.STARTING_ATTENDANCE_IMPORT, new StudentNameModel("", "", false), 0,
				" starting from " + startDate + " ***");

		// Get attendance data from Pike13 for all students
		ArrayList<AttendanceEventModel> eventList = pike13Api.getAttendance(startDate);

		// Update changes in database
		if (eventList.size() > 0)
			sqlDb.importAttendance(eventList);

		// Get 'missing' attendance for new and returned students
		ArrayList<StudentModel> newStudents = sqlDb.getStudentsUsingFlag("NewStudent");
		if (newStudents.size() > 0) {
			eventList = pike13Api.getMissingAttendance(startDate, newStudents);
			if (eventList.size() > 0)
				sqlDb.importAttendance(eventList);
		}

		sqlDb.insertLogData(LogDataModel.ATTENDANCE_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" starting from " + startDate + " ***");
	}

	public void importScheduleFromPike13(Pike13Api pike13Api) {
		String startDate = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles")).minusDays(14)
				.toString("yyyy-MM-dd");
		sqlDb.insertLogData(LogDataModel.STARTING_SCHEDULE_IMPORT, new StudentNameModel("", "", false), 0,
				" as of " + startDate.substring(0, 10) + " ***");

		// Get data from Pike13
		ArrayList<ScheduleModel> scheduleList = pike13Api.getSchedule(startDate);

		// Update changes in database
		if (scheduleList.size() > 0)
			sqlDb.importSchedule(scheduleList);

		sqlDb.insertLogData(LogDataModel.SCHEDULE_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" as of " + startDate.substring(0, 10) + " ***");
	}

	public void importGithubComments(String startDate, GithubApi githubApi) {
		boolean result;
		sqlDb.insertLogData(LogDataModel.STARTING_GITHUB_IMPORT, new StudentNameModel("", "", false), 0,
				" starting from " + startDate + " ***");

		// Get list of events with missing comments
		ArrayList<AttendanceEventModel> eventList = sqlDb.getEventsWithNoComments(startDate, 0, false);
		if (eventList.size() > 0) {
			// Import Github comments
			result = githubApi.importGithubComments(startDate, eventList);

			if (result) {
				// Import github comments for level 0 & 1
				githubApi.importGithubCommentsByLevel(0, startDate, null, eventList);
				githubApi.importGithubCommentsByLevel(1, startDate, null, eventList);

				// Updated github comments for users with new user name
				githubApi.updateMissingGithubComments();

				// Update any remaining null comments to show event was processed
				githubApi.updateEmptyGithubComments(eventList);

			} else {
				sqlDb.insertLogData(LogDataModel.GITHUB_IMPORT_ABORTED, new StudentNameModel("", "", false), 0,
						": Github API rate limit exceeded ***");
				return;
			}
		}

		sqlDb.insertLogData(LogDataModel.GITHUB_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" starting from " + startDate + " ***");
	}

	private String readFile(String filename) {
		try {
			File file = new File(filename);
			FileInputStream fis = new FileInputStream(file);

			byte[] data = new byte[(int) file.length()];
			fis.read(data);
			fis.close();

			return new String(data, "UTF-8");

		} catch (IOException e) {
			// Do nothing if file is not there
		}
		return "";
	}
}
