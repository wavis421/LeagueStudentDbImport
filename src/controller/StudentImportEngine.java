package controller;

import java.util.ArrayList;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import model.AttendanceEventModel;
import model.CoursesModel;
import model.LogDataModel;
import model.MySqlDatabase;
import model.MySqlDbImports;
import model.MySqlDbLogging;
import model.ScheduleModel;
import model.StudentImportModel;
import model.StudentModel;
import model.StudentNameModel;

public class StudentImportEngine {
	static final int SCHEDULE_DAYS_IN_PAST = 14;
	static final int COURSE_DAYS_IN_PAST = 14;
	static final int COURSE_DAYS_IN_FUTURE = 120;

	MySqlDatabase sqlDb;
	MySqlDbImports sqlImportDb;

	public StudentImportEngine(MySqlDatabase sqlDb, MySqlDbImports sqlImportDb) {
		this.sqlDb = sqlDb;
		this.sqlImportDb = sqlImportDb;
	}

	public void importStudentsFromPike13(Pike13Api pike13Api) {
		String today = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles")).toString("yyyy-MM-dd")
				.substring(0, 10);
		MySqlDbLogging.insertLogData(LogDataModel.STARTING_STUDENT_IMPORT, new StudentNameModel("", "", false), 0,
				" for " + today + " ***");

		// Get data from Pike13
		ArrayList<StudentImportModel> studentList = pike13Api.getClients();

		// Update changes in database
		if (studentList.size() > 0) {
			sqlImportDb.importStudents(studentList);
			System.out.println(studentList.size() + " students imported from Pike13");
		}

		MySqlDbLogging.insertLogData(LogDataModel.STUDENT_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" for " + today + " ***");
	}

	public void importAttendanceFromPike13(String startDate, Pike13Api pike13Api) {
		MySqlDbLogging.insertLogData(LogDataModel.STARTING_ATTENDANCE_IMPORT, new StudentNameModel("", "", false), 0,
				" starting from " + startDate + " ***");

		// Get attendance data from Pike13 for all students
		ArrayList<AttendanceEventModel> eventList = pike13Api.getAttendance(startDate);

		// Update changes in database
		if (eventList.size() > 0) {
			sqlImportDb.importAttendance(eventList);
			System.out.println(eventList.size() + " attendance records imported from Pike13");
		}

		// Get 'missing' attendance for new and returned students
		ArrayList<StudentModel> newStudents = sqlDb.getStudentsUsingFlag("NewStudent");
		if (newStudents.size() > 0) {
			eventList = pike13Api.getMissingAttendance(startDate, newStudents);
			if (eventList.size() > 0) {
				sqlImportDb.importAttendance(eventList);
				System.out.println(eventList.size() + " new student attendance records imported from Pike13");
			}
		}

		MySqlDbLogging.insertLogData(LogDataModel.ATTENDANCE_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" starting from " + startDate + " ***");
	}

	public void importCourseAttendanceFromPike13(String startDate, String endDate, Pike13Api pike13Api) {
		MySqlDbLogging.insertLogData(LogDataModel.STARTING_COURSE_ATTENDANCE_IMPORT, new StudentNameModel("", "", false), 0,
				" from " + startDate + " to " + endDate + " ***");

		// Get course attendance data from Pike13 for all students
		ArrayList<AttendanceEventModel> eventList = pike13Api.getCourseAttendance(startDate, endDate);

		// Update changes in database
		if (eventList.size() > 0) {
			sqlImportDb.importAttendance(eventList);
			System.out.println(eventList.size() + " course attendance records imported from Pike13, " + startDate
					+ " to " + endDate);
		}

		MySqlDbLogging.insertLogData(LogDataModel.COURSE_ATTENDANCE_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" from " + startDate + " to " + endDate + " ***");
	}

	public void importScheduleFromPike13(Pike13Api pike13Api) {
		String startDate = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles"))
				.minusDays(SCHEDULE_DAYS_IN_PAST).toString("yyyy-MM-dd");
		MySqlDbLogging.insertLogData(LogDataModel.STARTING_SCHEDULE_IMPORT, new StudentNameModel("", "", false), 0,
				" as of " + startDate.substring(0, 10) + " ***");

		// Get data from Pike13
		ArrayList<ScheduleModel> scheduleList = pike13Api.getSchedule(startDate);

		// Update changes in database
		if (scheduleList.size() > 0) {
			sqlImportDb.importSchedule(scheduleList);
			System.out.println(scheduleList.size() + " schedule records imported from Pike13");
		}

		MySqlDbLogging.insertLogData(LogDataModel.SCHEDULE_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" as of " + startDate.substring(0, 10) + " ***");
	}

	public void importCoursesFromPike13(Pike13Api pike13Api) {
		DateTime today = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles"));
		String startDate = today.minusDays(COURSE_DAYS_IN_PAST).toString("yyyy-MM-dd");
		String endDate = today.plusDays(COURSE_DAYS_IN_FUTURE).toString("yyyy-MM-dd");
		MySqlDbLogging.insertLogData(LogDataModel.STARTING_COURSES_IMPORT, new StudentNameModel("", "", false), 0,
				" from " + startDate + " to " + endDate + " ***");

		// Get data from Pike13
		ArrayList<CoursesModel> coursesList = pike13Api.getCourses(startDate, endDate);

		// Update changes in database
		if (coursesList.size() > 0) {
			sqlImportDb.importCourses(coursesList);
			System.out.println(coursesList.size() + " course records imported from Pike13");
		}

		MySqlDbLogging.insertLogData(LogDataModel.COURSES_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" from " + startDate + " to " + endDate + " ***");
	}

	public void importGithubComments(String startDate, GithubApi githubApi) {
		boolean result;
		MySqlDbLogging.insertLogData(LogDataModel.STARTING_GITHUB_IMPORT, new StudentNameModel("", "", false), 0,
				" starting from " + startDate + " ***");

		// Update github comments for users with new user name
		githubApi.updateMissingGithubComments();

		// Get list of events with missing comments
		ArrayList<AttendanceEventModel> eventList = sqlDb.getEventsWithNoComments(startDate, 0, false);
		if (eventList.size() > 0) {
			// Import Github comments
			result = githubApi.importGithubComments(startDate, eventList);

			if (result) {
				// Import github comments for level 0 & 1, plus Intro to Jave (-1)
				githubApi.importGithubCommentsByLevel(-1, startDate, null, eventList);
				githubApi.importGithubCommentsByLevel(0, startDate, null, eventList);
				githubApi.importGithubCommentsByLevel(1, startDate, null, eventList);

				// Update any remaining null comments to show event was processed
				githubApi.updateEmptyGithubComments(eventList);

				System.out.println(eventList.size() + " github records imported");

			} else {
				MySqlDbLogging.insertLogData(LogDataModel.GITHUB_IMPORT_ABORTED, new StudentNameModel("", "", false), 0,
						": Github API rate limit exceeded ***");
				return;
			}
		}

		MySqlDbLogging.insertLogData(LogDataModel.GITHUB_IMPORT_COMPLETE, new StudentNameModel("", "", false), 0,
				" starting from " + startDate + " ***");
	}
}
