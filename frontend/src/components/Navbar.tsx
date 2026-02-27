import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../AuthContext';

export default function Navbar() {
  const { isAuthenticated, role, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <nav className="bg-indigo-700 text-white shadow-lg">
      <div className="max-w-6xl mx-auto px-4 py-3 flex items-center justify-between">
        <Link to="/" className="text-xl font-bold tracking-tight">
          💈 Scheduler
        </Link>

        <div className="flex items-center gap-4">
          {/* Always show "Umów wizytę" link */}
          <Link to="/book" className="hover:text-indigo-200 transition">
            Umów wizytę
          </Link>

          {isAuthenticated ? (
            <>
              {role === 'customer' && (
                <>
                  <Link to="/my-reservations" className="hover:text-indigo-200 transition">
                    Moje wizyty
                  </Link>
                  <Link to="/profile" className="hover:text-indigo-200 transition">
                    Profil
                  </Link>
                </>
              )}
              {role === 'staff' && (
                <>
                  <Link to="/dashboard" className="hover:text-indigo-200 transition">
                    Panel
                  </Link>
                  <Link to="/services" className="hover:text-indigo-200 transition">
                    Usługi
                  </Link>
                  <Link to="/schedule" className="hover:text-indigo-200 transition">
                    Harmonogram
                  </Link>
                </>
              )}
              <button
                onClick={handleLogout}
                className="bg-indigo-500 hover:bg-indigo-400 px-3 py-1 rounded transition"
              >
                Wyloguj
              </button>
            </>
          ) : (
            <>
              <Link to="/login" className="hover:text-indigo-200 transition">
                Zaloguj się
              </Link>
              <Link to="/login-staff" className="hover:text-indigo-200 transition text-indigo-300 text-sm">
                Panel pracownika
              </Link>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
