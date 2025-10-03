import ballerina/io;

type Employee record {|
    int id;
    string name;
    string department;
    decimal salary;
|};

function analyzeEmployees(Employee[] employees, string department, decimal salary) =
    @natural:code {
        prompt: string `For each employee that belongs to the specified department
                            and has a salary greater than the given threshold print Employee info in the following format.
                            Employee ID: <Employee ID>, Name: <Employee Name>, Salary: <Employee Salary>`
    } external;

public function main() {
    Employee[] employees = [
        {id: 1, name: "Alice", department: "Engineering", salary: 60000.0},
        {id: 2, name: "Bob", department: "HR", salary: 45000.0},
        {id: 3, name: "Charlie", department: "Engineering", salary: 70000.0},
        {id: 4, name: "David", department: "Marketing", salary: 52000.0},
        {id: 5, name: "Eve", department: "Engineering", salary: 48000.0}
    ];

    string department = "Engineering";
    decimal salaryThreshold = 50000.0;
    io:println("Analyzing employees in department: ", department, " with salary greater than ", salaryThreshold);
    analyzeEmployees(employees, department, salaryThreshold);
}
